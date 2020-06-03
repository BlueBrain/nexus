package ch.epfl.bluebrain.nexus.admin.persistence

import akka.persistence.journal.{Tagged, WriteEventAdapter}
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.admin.persistence.TaggingAdapter._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent

/**
  * A tagging event adapter that adds tags to discriminate between event hierarchies.
  */
class TaggingAdapter extends WriteEventAdapter {

  override def manifest(event: Any): String = event match {
    case _: ProjectEvent      => ProjectTag
    case _: OrganizationEvent => OrganizationTag
  }

  override def toJournal(event: Any): Any = event match {
    case pe: ProjectEvent      => Tagged(pe, Set(ProjectTag, EventTag))
    case po: OrganizationEvent => Tagged(po, Set(OrganizationTag, EventTag))
    case _                     => event
  }
}

object TaggingAdapter {

  /**
    * Tag applied to projects.
    */
  final val ProjectTag: String = "project"

  /**
    * Tag applied to organizations.
    */
  final val OrganizationTag: String = "organization"

  /**
    * Tag applied to all events.
    */
  final val EventTag: String = "event"
}
