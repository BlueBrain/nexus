package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Empty
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri

/**
  * A term definition is an entry in a context, where the key defines a term which may be used within a JSON object
  * as a property, type, or elsewhere that a string is interpreted as a vocabulary item.
  */
sealed trait TermDefinition extends Product with Serializable {
  def id: Uri
  def context: EmptyNullOr[Context]
  def reverse: Option[Uri]
  def container: Set[String]
  def tpe: Option[KeywordOrUri]
  def termLanguage: EmptyNullOr[LanguageTag]
  def termDirection: EmptyNullOr[String]
  def withContext(context: EmptyNullOr[Context]): TermDefinition
  def withContainer(container: Set[String]): TermDefinition
}

object TermDefinition {

  /**
    * The associated expanded form Uri of a term
    */
  final case class SimpleTermDefinition(id: Uri) extends TermDefinition {
    val context: EmptyNullOr[Context]                              = Empty
    val container: Set[String]                                     = Set.empty
    val reverse: Option[Uri]                                       = None
    val termLanguage: EmptyNullOr[LanguageTag]                     = Empty
    val termDirection: EmptyNullOr[String]                         = Empty
    val tpe: Option[KeywordOrUri]                                  = None
    def withContext(context: EmptyNullOr[Context]): TermDefinition = ExpandedTermDefinition(id, context = context)
    def withContainer(container: Set[String]): TermDefinition      = ExpandedTermDefinition(id, container = container)
  }

  /**
    * An expanded term definition, is a term definition with extra information
    *
    * @param id          the associated expanded form Uri of a term
    * @param tpe         optionally defines how to coerce a term. It's value should be a String that expands to an Uri.
    *                    Alternatively, the keywords '@id' or '@vocab' can be used
    * @param container   sets the default container(s) for a term. Allowed keywords are: @set, @list, @graph, @language, @type, @index, @id
    * @param language    optionally sets language for this term
    * @param prefix      flag to decide whether or not the term associated with this definition can use the @id value to resolve
    *                    prefixes even if if does not end with a gen-delim
    * @param `protected` the optional flag to prevent re-definition of the same term
    * @param propagate   flag sed in a context definition to change the scope of that context. When set to false, the context will
    *                    not propagate outside of the scope of the current definition; while setting it to true it will propagate
    *                    to the rest of the object nodes
    * @param direction   the optional direction of a string value, with possible values "ltr" or "rtl"
    * @param reverse     optional used to express reverse property
    * @param index       optional used to express the Uri which will be added to each node object when used in combination with @containier: @index
    *                    definition
    * @param nest        defines a term as nested. Possible values are '@nest' or a term.
    *                    when the value is @nest: the term won't be expanded
    *                    otherwise, this term will be used when converting from expanded -> compacted form
    */
  final case class ExpandedTermDefinition(
      id: Uri,
      tpe: Option[KeywordOrUri] = None,
      container: Set[String] = Set.empty,
      language: EmptyNullOr[LanguageTag] = Empty,
      prefix: Boolean = false,
      `protected`: Option[Boolean] = None,
      propagate: Option[Boolean] = None,
      direction: EmptyNullOr[String] = Empty,
      reverse: Option[Uri] = None,
      index: Option[Uri] = None,
      nest: Option[String] = None,
      context: EmptyNullOr[Context] = Empty
  ) extends TermDefinition {
    def termLanguage: EmptyNullOr[LanguageTag]                     = language.onNone(context.flatMap(_.language))
    def termDirection: EmptyNullOr[String]                         = direction.onNone(context.flatMap(_.direction))
    def withContext(context: EmptyNullOr[Context]): TermDefinition = copy(context = context)
    def withContainer(container: Set[String]): TermDefinition      = copy(container = container)
  }

  implicit final def termDefinitionFromUri(uri: Uri): SimpleTermDefinition = SimpleTermDefinition(uri)

  /**
    * Enumeration of types when a value can be either a Uri or a Json-LD keyword
    */
  sealed trait KeywordOrUri extends Product with Serializable {
    def asUri: Option[Uri]
    def asKeyword: Option[String]
  }

  object KeywordOrUri {
    final case class UriValue(value: Uri) extends KeywordOrUri {
      val asUri: Option[Uri]        = Some(value)
      val asKeyword: Option[String] = None
    }
    final case class KeywordValue(value: String) extends KeywordOrUri {
      val asUri: Option[Uri]        = None
      val asKeyword: Option[String] = Some(value)
    }

    implicit final def keywordFromUri(uri: Uri): KeywordOrUri       = UriValue(uri)
    implicit final def keywordFromString(str: String): KeywordOrUri = KeywordValue(str)

  }

}
