package ch.epfl.bluebrain.nexus.iam.io

import akka.persistence.journal.{Tagged, WriteEventAdapter}
import ch.epfl.bluebrain.nexus.iam.acls.AclEvent
import ch.epfl.bluebrain.nexus.iam.io.TaggingAdapter._
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.iam.realms.RealmEvent

/**
  * A tagging event adapter that adds tags to discriminate between event hierarchies.
  */
class TaggingAdapter extends WriteEventAdapter {

  override def manifest(event: Any): String =
    event match {
      case _: PermissionsEvent => "permissions-event"
      case _: AclEvent         => "acl-event"
      case _: RealmEvent       => "realm-event"
      case _                   => ""
    }

  override def toJournal(event: Any): Any =
    event match {
      case ev: PermissionsEvent => Tagged(ev, Set(PermissionsEventTag, EventTag))
      case ev: AclEvent         => Tagged(ev, Set(AclEventTag, EventTag))
      case ev: RealmEvent       => Tagged(ev, Set(RealmEventTag, EventTag))
      case _                    => event
    }
}

object TaggingAdapter {
  final val EventTag            = "event"
  final val PermissionsEventTag = "permissions"
  final val AclEventTag         = "acl"
  final val RealmEventTag       = "realm"
}
