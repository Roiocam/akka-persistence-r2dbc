/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.r2dbc.internal

import scala.collection.immutable
import java.time.Instant
import java.time.{ Duration => JDuration }
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import akka.NotUsed
import akka.actor.typed.scaladsl.LoggerOps
import akka.annotation.InternalApi
import akka.persistence.query.Offset
import akka.persistence.query.TimestampOffset
import akka.persistence.r2dbc.R2dbcSettings
import akka.persistence.r2dbc.internal.BySliceQuery.Buckets.Bucket
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import org.slf4j.Logger

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] object BySliceQuery {
  val EmptyDbTimestamp: Instant = Instant.EPOCH

  object QueryState {
    val empty: QueryState =
      QueryState(TimestampOffset.Zero, 0, 0, 0, backtrackingCount = 0, TimestampOffset.Zero, Buckets.empty)
  }

  final case class QueryState(
      latest: TimestampOffset,
      rowCount: Int,
      queryCount: Long,
      idleCount: Long,
      backtrackingCount: Int,
      latestBacktracking: TimestampOffset,
      buckets: Buckets) {

    def backtracking: Boolean = backtrackingCount > 0

    def currentOffset: TimestampOffset =
      if (backtracking) latestBacktracking
      else latest

    def nextQueryFromTimestamp: Instant =
      if (backtracking) latestBacktracking.timestamp
      else latest.timestamp

    /**
     * 下次查询的时间窗口
     * @param atLeastNumberOfEvents
     * @return
     */
    def nextQueryToTimestamp(atLeastNumberOfEvents: Int): Option[Instant] = {
      // 在 buckets 中查询 Limit 的 Time
      buckets.findTimeForLimit(nextQueryFromTimestamp, atLeastNumberOfEvents) match {
        case Some(t) =>
          if (backtracking)
            if (t.isAfter(latest.timestamp)) Some(latest.timestamp) else Some(t)
          else
            Some(t)
        case None =>
          // 如果不存在, 返回 null 或者已读取的最大时间.
          if (backtracking) Some(latest.timestamp)
          else None
      }
    }
  }

  object Buckets {
    type EpochSeconds = Long
    type Count = Long

    val empty = new Buckets(immutable.SortedMap.empty)
    // Note that 10 seconds is also defined in the aggregation sql in the dao, so be cautious if you change this.
    val BucketDurationSeconds = 10
    val Limit = 10000

    final case class Bucket(startTime: EpochSeconds, count: Count)
  }

  /**
   * Count of events or state changes per 10 seconds time bucket is retrieved from database (infrequently) with an
   * aggregation query. This is used for estimating an upper bound of `db_timestamp < ?` in the `eventsBySlices` and
   * `changesBySlices` database queries. It is important to reduce the result set in this way because the `LIMIT` is
   * used after sorting the rows. See issue #/178 for more background info..
   *
   * @param countByBucket
   *   Key is the epoch seconds for the start of the bucket. Value is the number of entries in the bucket.
   */
  class Buckets(countByBucket: immutable.SortedMap[Buckets.EpochSeconds, Buckets.Count]) {
    import Buckets.{ Bucket, BucketDurationSeconds, Count, EpochSeconds }

    val createdAt: Instant = Instant.now()

    def findTimeForLimit(from: Instant, atLeastCounts: Int): Option[Instant] = {
      // begin 的时间戳
      val fromEpochSeconds = from.toEpochMilli / 1000
      // 移除事件 == 开始读取的
      val iter = countByBucket.iterator.dropWhile { case (key, _) => fromEpochSeconds >= key }

      //
      @tailrec def sumUntilFilled(key: EpochSeconds, sum: Count): (EpochSeconds, Count) = {
        if (iter.isEmpty || sum >= atLeastCounts)
          key -> sum
        else {
          val (nextKey, count) = iter.next()
          sumUntilFilled(nextKey, sum + count)
        }
      }
      // 算出每个时间戳内的事件数量
      val (key, sum) = sumUntilFilled(fromEpochSeconds, 0)
      // 如果 sum >= 最少的数量
      if (sum >= atLeastCounts)
        Some(Instant.ofEpochSecond(key + BucketDurationSeconds))
      else
        None
    }

    // Key is the epoch seconds for the start of the bucket.
    // Value is the number of entries in the bucket.
    def add(bucketCounts: Seq[Bucket]): Buckets =
      new Buckets(countByBucket ++ bucketCounts.iterator.map { case Bucket(startTime, count) => startTime -> count })

    def clearUntil(time: Instant): Buckets = {
      val epochSeconds = time.minusSeconds(BucketDurationSeconds).toEpochMilli / 1000
      val newCountByBucket = countByBucket.dropWhile { case (key, _) => epochSeconds >= key }
      if (newCountByBucket.size == countByBucket.size)
        this
      else if (newCountByBucket.isEmpty)
        new Buckets(immutable.SortedMap(countByBucket.last)) // keep last
      else
        new Buckets(newCountByBucket)
    }

    def isEmpty: Boolean = countByBucket.isEmpty

    def size: Int = countByBucket.size

    override def toString: String = {
      s"Buckets(${countByBucket.mkString(", ")})"
    }
  }

  trait SerializedRow {
    def persistenceId: String
    def seqNr: Long
    def dbTimestamp: Instant
    def readDbTimestamp: Instant
  }

  trait Dao[SerializedRow] {
    def currentDbTimestamp(): Future[Instant]

    def rowsBySlices(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        fromTimestamp: Instant,
        toTimestamp: Option[Instant],
        behindCurrentTime: FiniteDuration,
        backtracking: Boolean): Source[SerializedRow, NotUsed]

    /**
     * For Durable State we always refresh the bucket counts at the interval. For Event Sourced we know that they don't
     * change because events are append only.
     */
    def countBucketsMayChange: Boolean

    def countBuckets(
        entityType: String,
        minSlice: Int,
        maxSlice: Int,
        fromTimestamp: Instant,
        limit: Int): Future[Seq[Bucket]]

  }
}

/**
 * INTERNAL API
 */
@InternalApi private[r2dbc] class BySliceQuery[Row <: BySliceQuery.SerializedRow, Envelope](
    dao: BySliceQuery.Dao[Row],
    createEnvelope: (TimestampOffset, Row) => Envelope,
    extractOffset: Envelope => TimestampOffset,
    settings: R2dbcSettings,
    log: Logger)(implicit val ec: ExecutionContext) {
  import BySliceQuery._
  import TimestampOffset.toTimestampOffset

  private val backtrackingWindow = JDuration.ofMillis(settings.querySettings.backtrackingWindow.toMillis)
  private val halfBacktrackingWindow = backtrackingWindow.dividedBy(2)
  private val firstBacktrackingQueryWindow =
    backtrackingWindow.plus(JDuration.ofMillis(settings.querySettings.backtrackingBehindCurrentTime.toMillis))
  private val eventBucketCountInterval = JDuration.ofSeconds(60)

  def currentBySlices(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[Envelope, NotUsed] = {
    val initialOffset = toTimestampOffset(offset)

    def nextOffset(state: QueryState, envelope: Envelope): QueryState =
      state.copy(latest = extractOffset(envelope), rowCount = state.rowCount + 1)

    def nextQuery(state: QueryState, endTimestamp: Instant): (QueryState, Option[Source[Envelope, NotUsed]]) = {
      // Note that we can't know how many events with the same timestamp that are filtered out
      // so continue until rowCount is 0. That means an extra query at the end to make sure there are no
      // more to fetch.
      if (state.queryCount == 0L || state.rowCount > 0) {
        val newState = state.copy(rowCount = 0, queryCount = state.queryCount + 1)

        val toTimestamp = newState.nextQueryToTimestamp(settings.querySettings.bufferSize) match {
          case Some(t) =>
            if (t.isBefore(endTimestamp)) t else endTimestamp
          case None =>
            endTimestamp
        }

        if (state.queryCount != 0 && log.isDebugEnabled())
          log.debugN(
            "{} next query [{}] from slices [{} - {}], between time [{} - {}]. Found [{}] rows in previous query.",
            logPrefix,
            state.queryCount,
            minSlice,
            maxSlice,
            state.latest.timestamp,
            toTimestamp,
            state.rowCount)

        newState -> Some(
          dao
            .rowsBySlices(
              entityType,
              minSlice,
              maxSlice,
              state.latest.timestamp,
              toTimestamp = Some(toTimestamp),
              behindCurrentTime = Duration.Zero,
              backtracking = false)
            .via(deserializeAndAddOffset(state.latest)))
      } else {
        if (log.isDebugEnabled)
          log.debugN(
            "{} query [{}] from slices [{} - {}] completed. Found [{}] rows in previous query.",
            logPrefix,
            state.queryCount,
            minSlice,
            maxSlice,
            state.rowCount)

        state -> None
      }
    }

    val currentTimestamp =
      if (settings.useAppTimestamp) Future.successful(InstantFactory.now())
      else dao.currentDbTimestamp()

    Source
      .futureSource[Envelope, NotUsed] {
        dao.currentDbTimestamp().map { currentDbTime =>
          if (log.isDebugEnabled())
            log.debugN(
              "{} query slices [{} - {}], from time [{}] until now [{}].",
              logPrefix,
              minSlice,
              maxSlice,
              initialOffset.timestamp,
              currentTime)

          ContinuousQuery[QueryState, Envelope](
            initialState = QueryState.empty.copy(latest = initialOffset),
            updateState = nextOffset,
            delayNextQuery = _ => None,
            nextQuery = state => nextQuery(state, currentTime),
            beforeQuery = beforeQuery(logPrefix, entityType, minSlice, maxSlice, _))
        }
      }
      .mapMaterializedValue(_ => NotUsed)
  }

  def liveBySlices(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      offset: Offset): Source[Envelope, NotUsed] = {
    // offset 变成基于时间的 offset
    val initialOffset = toTimestampOffset(offset)

    if (log.isDebugEnabled())
      log.debugN(
        "Starting {} query from slices [{} - {}], from time [{}].",
        logPrefix,
        minSlice,
        maxSlice,
        initialOffset.timestamp)

    def nextOffset(state: QueryState, envelope: Envelope): QueryState = {
      // 从事件中提取 offset
      val offset = extractOffset(envelope)
      if (state.backtracking) {
        if (offset.timestamp.isBefore(state.latestBacktracking.timestamp))
          throw new IllegalArgumentException(
            s"Unexpected offset [$offset] before latestBacktracking [${state.latestBacktracking}].")
        // 返回当前 offset
        state.copy(latestBacktracking = offset, rowCount = state.rowCount + 1)
      } else {
        if (offset.timestamp.isBefore(state.latest.timestamp))
          throw new IllegalArgumentException(s"Unexpected offset [$offset] before latest [${state.latest}].")
        // 返回更新 offset 后的 offset
        state.copy(latest = offset, rowCount = state.rowCount + 1)
      }
    }

    def delayNextQuery(state: QueryState): Option[FiniteDuration] = {
      // 如果切换回溯成功, 立即进入
      if (switchFromBacktracking(state)) {
        // switch from from backtracking immediately
        None
      } else {
        // 否则降频
        // 调整下一延迟
        val delay = ContinuousQuery.adjustNextDelay(
          state.rowCount, // 行数
          settings.querySettings.bufferSize, // 缓冲大小
          settings.querySettings.refreshInterval) // 刷新频率

        if (log.isDebugEnabled)
          delay.foreach { d =>
            log.debugN(
              "{} query [{}] from slices [{} - {}] delay next [{}] ms.",
              logPrefix,
              state.queryCount,
              minSlice,
              maxSlice,
              d.toMillis)
          }

        delay
      }
    }

    def switchFromBacktracking(state: QueryState): Boolean = {
      // backtrackingCount is for fairness, to not run too many backtracking queries in a row
      // 回溯计数是未了公平性, 为了不连续运行太多的回溯查询
      state.backtracking && state.rowCount < settings.querySettings.bufferSize - 1
    }

    def nextQuery(state: QueryState): (QueryState, Option[Source[Envelope, NotUsed]]) = {
      // 新的空转数量
      val newIdleCount = if (state.rowCount == 0) state.idleCount + 1 else 0
      // 新的状态（当前读取时间，最后读取时间）
      // 如果开启了回溯 && 当前不在回溯过程中 && 查询状态的最后时间戳不等于 0 && (新空转次数 >= 5 || 最后回溯时间和最后时间戳大于半个回溯窗口 )
      val newState =
        if (settings.querySettings.backtrackingEnabled && !state.backtracking && state.latest != TimestampOffset.Zero &&
          (newIdleCount >= 5 ||
          state.rowCountSinceBacktracking + state.rowCount >= settings.querySettings.bufferSize * 3 ||
          JDuration
            .between(state.latestBacktracking.timestamp, state.latest.timestamp)
            .compareTo(halfBacktrackingWindow) > 0)) {
          // FIXME config for newIdleCount >= 5 and maybe something like `newIdleCount % 5 == 0`
          // 切换到回溯状态

          // Note that when starting the query with offset = NoOffset it will switch to backtracking immediately after
          // the first normal query because between(latestBacktracking.timestamp, latest.timestamp) > halfBacktrackingWindow

          // switching to backtracking
          // 回溯开始的时间戳(offset)
          val fromOffset =
            if (state.latestBacktracking == TimestampOffset.Zero)
              TimestampOffset.Zero.copy(timestamp = state.latest.timestamp.minus(firstBacktrackingQueryWindow))
            else
              state.latestBacktracking
          // 进入回溯状态
          state.copy(
            rowCount = 0,
            rowCountSinceBacktracking = 0,
            queryCount = state.queryCount + 1,
            idleCount = newIdleCount,
            backtrackingCount = 1,
            latestBacktracking = fromOffset)
        } else if (switchFromBacktracking(state)) {
          // 从回溯查询中切换回普通状态.
          // switch from backtracking
          state.copy(
            rowCount = 0,
            rowCountSinceBacktracking = 0,
            queryCount = state.queryCount + 1,
            idleCount = newIdleCount,
            backtrackingCount = 0)
        } else {
          // 执行下一次查询, 或者下一次回溯
          // continue
          val newBacktrackingCount = if (state.backtracking) state.backtrackingCount + 1 else 0
          state.copy(
            rowCount = 0,
            rowCountSinceBacktracking = state.rowCountSinceBacktracking + state.rowCount,
            queryCount = state.queryCount + 1,
            idleCount = newIdleCount,
            backtrackingCount = newBacktrackingCount)
        }
      // 截止到当前时间戳.
      val behindCurrentTime =
        if (newState.backtracking) settings.querySettings.backtrackingBehindCurrentTime
        else settings.querySettings.behindCurrentTime
      // 从 from 到 to 时间戳
      val fromTimestamp = newState.nextQueryFromTimestamp
      val toTimestamp = newState.nextQueryToTimestamp(settings.querySettings.bufferSize)

      if (log.isDebugEnabled()) {
        val backtrackingInfo =
          if (newState.backtracking && !state.backtracking)
            s" switching to backtracking mode, [${state.rowCountSinceBacktracking + state.rowCount}] events behind,"
          else if (!newState.backtracking && state.backtracking)
            " switching from backtracking mode,"
          else if (newState.backtracking && state.backtracking)
            " in backtracking mode,"
          else
            ""
        log.debugN(
          "{} next query [{}]{} from slices [{} - {}], between time [{} - {}]. {}",
          logPrefix,
          newState.queryCount,
          backtrackingInfo,
          minSlice,
          maxSlice,
          fromTimestamp,
          toTimestamp.getOrElse("None"),
          if (newIdleCount >= 3) s"Idle in [$newIdleCount] queries."
          else if (state.backtracking) s"Found [${state.rowCount}] rows in previous backtracking query."
          else s"Found [${state.rowCount}] rows in previous query.")
      }

      newState ->
      // 最终调用 dao.rowsBySlices 查询
      Some(
        dao
          .rowsBySlices(
            entityType,
            minSlice,
            maxSlice,
            fromTimestamp,
            toTimestamp,
            behindCurrentTime,
            backtracking = newState.backtracking)
          .via(deserializeAndAddOffset(newState.currentOffset)))
    }

    // 定义持续查询
    ContinuousQuery[QueryState, Envelope](
      initialState = QueryState.empty.copy(latest = initialOffset), // 初始化状态：一开始读取的 offset
      updateState = nextOffset,                                     // 更新状态：状态更新用的方法，递增 offset
      delayNextQuery = delayNextQuery,                              // 延迟的下一次查询用：调整下一次查询的行数、缓冲大小、刷新时间等
      nextQuery = nextQuery,                                        // 下次查询：拿到时间戳、更新 queryCount 等，最终使用 dao.rowsBySlices 查询事件
      beforeQuery = beforeQuery(logPrefix, entityType, minSlice, maxSlice, _))  // 查询之前：这里获取 countBucket 的数量（似乎是这次查询的总数）
  }

  private def beforeQuery(
      logPrefix: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int,
      state: QueryState): Option[Future[QueryState]] = {
    // Don't run this too frequently
    if ((state.buckets.isEmpty || JDuration
        .between(state.buckets.createdAt, InstantFactory.now())
        .compareTo(eventBucketCountInterval) > 0) &&
      // For Durable State we always refresh the bucket counts at the interval. For Event Sourced we know
      // that they don't change because events are append only.
      (dao.countBucketsMayChange || state.buckets
        .findTimeForLimit(state.latest.timestamp, settings.querySettings.bufferSize)
        .isEmpty)) {

      val fromTimestamp =
        if (state.latestBacktracking.timestamp == Instant.EPOCH && state.latest.timestamp == Instant.EPOCH)
          Instant.EPOCH
        else if (state.latestBacktracking.timestamp == Instant.EPOCH)
          state.latest.timestamp.minus(firstBacktrackingQueryWindow)
        else
          state.latestBacktracking.timestamp

      val futureState =
        dao.countBuckets(entityType, minSlice, maxSlice, fromTimestamp, Buckets.Limit).map { counts =>
          val newBuckets = state.buckets.clearUntil(fromTimestamp).add(counts)
          val newState = state.copy(buckets = newBuckets)
          if (log.isDebugEnabled) {
            val sum = counts.iterator.map { case Bucket(_, count) => count }.sum
            log.debugN(
              "{} retrieved [{}] event count buckets, with a total of [{}], from slices [{} - {}], from time [{}]",
              logPrefix,
              counts.size,
              sum,
              minSlice,
              maxSlice,
              fromTimestamp)
          }
          newState
        }
      Some(futureState)
    } else {
      // already enough buckets or retrieved recently
      None
    }
  }

  // TODO Unit test in isolation
  private def deserializeAndAddOffset(timestampOffset: TimestampOffset): Flow[Row, Envelope, NotUsed] = {
    Flow[Row].statefulMapConcat { () =>
      var currentTimestamp = timestampOffset.timestamp
      var currentSequenceNrs: Map[String, Long] = timestampOffset.seen
      row => {
        if (row.dbTimestamp == currentTimestamp) {
          // has this already been seen?
          if (currentSequenceNrs.get(row.persistenceId).exists(_ >= row.seqNr)) {
            if (currentSequenceNrs.size >= settings.querySettings.bufferSize) {
              throw new IllegalStateException(
                s"Too many events stored with the same timestamp [$currentTimestamp], buffer size [${settings.querySettings.bufferSize}]")
            }
            log.traceN(
              "filtering [{}] [{}] as db timestamp is the same as last offset and is in seen [{}]",
              row.persistenceId,
              row.seqNr,
              currentSequenceNrs)
            Nil
          } else {
            currentSequenceNrs = currentSequenceNrs.updated(row.persistenceId, row.seqNr)
            val offset =
              TimestampOffset(row.dbTimestamp, row.readDbTimestamp, currentSequenceNrs)
            createEnvelope(offset, row) :: Nil
          }
        } else {
          // ne timestamp, reset currentSequenceNrs
          currentTimestamp = row.dbTimestamp
          currentSequenceNrs = Map(row.persistenceId -> row.seqNr)
          val offset = TimestampOffset(row.dbTimestamp, row.readDbTimestamp, currentSequenceNrs)
          createEnvelope(offset, row) :: Nil
        }
      }
    }
  }
}
