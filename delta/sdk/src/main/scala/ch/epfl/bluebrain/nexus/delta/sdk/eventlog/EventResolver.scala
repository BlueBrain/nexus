package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF}
import monix.bio.Task

/**
  * Resolver which allows to fetch the latest state for an [[Event]].
  */
trait EventResolver {

  /**
    * The type of [[Event]] this resolver resolves
    */
  def eventType: String

  /**
    * Fetch the latest state for a given [[Event]]
    *
    * @param e the event for which to fetch the latest state.
    */
  def latestStateAsExpandedJsonLd(e: Event): Task[Option[ResourceF[ExpandedJsonLd]]]

}
