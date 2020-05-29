package ch.epfl.bluebrain.nexus.commons.sparql.client

import akka.http.scaladsl.model.Uri

/**
  * A description of a graph patch operation.
  */
sealed trait PatchStrategy

object PatchStrategy {

  /**
    * Constructs a patch strategy that removes triples that have the specified predicates from the target graph.
    *
    * @param predicates the predicates that select the triples to be removed
    */
  def removePredicates(predicates: Set[Uri]): PatchStrategy =
    RemovePredicates(predicates)

  /**
    * Constructs a patch strategy that removes all triples that do not contain the specified predicates from the target
    * graph.
    *
    * @param predicates the predicates that select the triples to be retained
    */
  def removeButPredicates(predicates: Set[Uri]): PatchStrategy =
    RemoveButPredicates(predicates)
}

private[client] final case class RemovePredicates(predicates: Set[Uri])    extends PatchStrategy
private[client] final case class RemoveButPredicates(predicates: Set[Uri]) extends PatchStrategy
