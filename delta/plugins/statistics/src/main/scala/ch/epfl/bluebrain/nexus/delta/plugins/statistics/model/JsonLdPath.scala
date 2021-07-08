package ch.epfl.bluebrain.nexus.delta.plugins.statistics.model

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPath.JsonLdPathEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

import scala.annotation.tailrec

/**
  * A JSON-LD enumeration of possible paths
  */
sealed trait JsonLdPath extends Product with Serializable {
  def parent: JsonLdPath
  def asPathEntry: Option[JsonLdPathEntry]
}

object JsonLdPath {

  /**
    * The Root path
    */
  final case object RootPath extends JsonLdPath { self =>
    override val parent: JsonLdPath                   = self
    override val asPathEntry: Option[JsonLdPathEntry] = None
  }

  /**
    * The enumeration of path entries (entries with predicates)
    */
  sealed trait JsonLdPathEntry extends JsonLdPath { self =>

    def predicate: Iri

    def predicates: Seq[Iri] = {
      @tailrec
      def inner(acc: List[Iri], current: JsonLdPath): List[Iri] =
        current match {
          case RootPath                 => acc
          case current: JsonLdPathEntry => inner(current.predicate :: acc, current.parent)
        }

      inner(List.empty, self)
    }

    def isInArray: Boolean = {
      @tailrec
      def inner(current: JsonLdPath): Boolean =
        current match {
          case RootPath          => false
          case _: ArrayPathEntry => true
          case _                 => inner(current.parent)
        }

      inner(self)
    }

    override val asPathEntry: Option[JsonLdPathEntry] = Some(self)
  }

  final case class ObjectPathEntry(predicate: Iri, parent: JsonLdPath) extends JsonLdPathEntry
  final case class ArrayPathEntry(predicate: Iri, parent: JsonLdPath)  extends JsonLdPathEntry
}
