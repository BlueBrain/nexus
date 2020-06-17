package ch.epfl.bluebrain.nexus.kg.persistence

import akka.persistence.journal.{Tagged, WriteEventAdapter}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.kg.persistence.TaggingAdapter.EventTag
import ch.epfl.bluebrain.nexus.kg.resources.Event.{Created, Deprecated, Updated}
import ch.epfl.bluebrain.nexus.kg.resources.{Event, Id, OrganizationRef}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef

import ch.epfl.bluebrain.nexus.rdf.Iri

/**
  * A tagging event adapter that adds tags to discriminate between event hierarchies.
  */
class TaggingAdapter extends WriteEventAdapter {

  override def manifest(event: Any): String = ""

  def tagsFrom(id: Id[ProjectRef], organization: OrganizationRef, types: Set[Iri.AbsoluteIri]): Set[String] =
    types.map(t => s"type=${t.show}") + s"project=${id.parent.id}" + s"org=${organization.show}" + EventTag

  override def toJournal(event: Any): Any =
    event match {
      case Created(id, org, _, types, _, _, _) => Tagged(event, tagsFrom(id, org, types))
      case Updated(id, org, _, types, _, _, _) => Tagged(event, tagsFrom(id, org, types))
      case Deprecated(id, org, _, types, _, _) => Tagged(event, tagsFrom(id, org, types))
      case ev: Event                           => Tagged(ev, tagsFrom(ev.id, ev.organization, types = Set.empty))
      case _                                   => event
    }
}

object TaggingAdapter {

  /**
    * The tag applied to all the events.
    */
  val EventTag: String = "event"
}
