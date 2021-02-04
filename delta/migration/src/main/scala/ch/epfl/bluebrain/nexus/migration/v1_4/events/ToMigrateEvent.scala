package ch.epfl.bluebrain.nexus.migration.v1_4.events

trait ToMigrateEvent extends Product with Serializable

object ToMigrateEvent {
  final case object EmptyEvent extends ToMigrateEvent
  val empty: ToMigrateEvent = EmptyEvent
}
