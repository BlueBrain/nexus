package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.Uri

/**
  * A description of a graph patch operation.
  */
sealed trait PatchStrategy

object PatchStrategy {

  /**
    * Constructs a patch strategy that removes triples that have the specified predicates from the target graph.
    *
    * @param predicates
    *   the predicates that select the triples to be removed
    */
  def removePredicates(predicates: Set[Uri]): PatchStrategy =
    RemovePredicates(predicates)

  /**
    * Constructs a patch strategy that removes all triples that do not contain the specified predicates from the target
    * graph.
    *
    * @param predicates
    *   the predicates that select the triples to be retained
    */
  def keepPredicates(predicates: Set[Uri]): PatchStrategy =
    RemoveButPredicates(predicates)
}

final private[client] case class RemovePredicates(predicates: Set[Uri])    extends PatchStrategy
final private[client] case class RemoveButPredicates(predicates: Set[Uri]) extends PatchStrategy
