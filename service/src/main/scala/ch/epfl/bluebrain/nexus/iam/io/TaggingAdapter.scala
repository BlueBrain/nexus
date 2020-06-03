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

  override def manifest(event: Any): String = event match {
    case _: PermissionsEvent => "permissions-event"
    case _: AclEvent         => "acl-event"
    case _: RealmEvent       => "realm-event"
    case _                   => ""
  }

  override def toJournal(event: Any): Any = event match {
    case ev: PermissionsEvent => Tagged(ev, Set(permissionsEventTag, eventTag))
    case ev: AclEvent         => Tagged(ev, Set(aclEventTag, eventTag))
    case ev: RealmEvent       => Tagged(ev, Set(realmEventTag, eventTag))
    case _                    => event
  }
}

object TaggingAdapter {
  final val eventTag            = "event"
  final val permissionsEventTag = "permissions"
  final val aclEventTag         = "acl"
  final val realmEventTag       = "realm"
}
