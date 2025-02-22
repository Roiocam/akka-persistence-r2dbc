# Snapshot store plugin

The snapshot plugin enables storing and loading snapshots for
@extref:[event sourced persistent actors](akka:typed/persistence.html).

## Schema

The `snapshot` table need to be created in the configured database, see schema definition in
@ref:[Creating the schema](getting-started.md#schema).

## Configuration

To enable the snapshot plugin to be used by default, add the following line to your Akka `application.conf`:

```
akka.persistence.snapshot-store.plugin = "akka.persistence.r2dbc.snapshot"
```

It can also be enabled with the `snapshotPluginId` for a specific `EventSourcedBehavior` and multiple plugin
configurations are supported.

See also @ref:[Connection configuration](config.md#connection-configuration).

### Reference configuration

The following can be overridden in your `application.conf` for the snapshot specific settings:

@@snip [reference.conf](/core/src/main/resources/reference.conf) {#snapshot-settings}

## Usage

The snapshot plugin is used whenever a snapshot write is triggered through the
@extref:[Akka Persistence APIs](akka:typed/persistence-snapshot.html).

## Retention

The R2DBC snapshot plugin only ever keeps *one* snapshot per persistence id in the database. If a `keepNSnapshots > 1`
is specified for an `EventSourcedBehavior` that setting will be ignored.

The reason for this is that there is no real benefit to keep multiple snapshots around on a relational database with a
high consistency.

See also @ref[EventSourcedCleanup tool](cleanup.md#event-sourced-cleanup-tool).
