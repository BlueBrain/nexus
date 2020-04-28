package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.iri.Curie
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.{RelativeIri, Uri}
import ch.epfl.bluebrain.nexus.rdf.jsonld.{EmptyNullOr, JsonLdOptions}
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.{Empty, Null, Val}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.Context._
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser
import io.circe.Decoder
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._

/**
  * A context with its term definitions
  *
  * @param terms       the term definitions
  * @param keywords    the aliases for the Json-LD keywords as a map, where the keys are the keywords and the values a set of aliases
  * @param vocab       the optional @vocab Uri. This Uri will be used as a prefix to resolve properties and types
  *                    (when they don't match another term on the @context or they are not a curie form)
  * @param base        the optional @base Uri. This Uri will be used to resolve values of the term @id
  *                    in the rest of the Json document
  * @param version11   flag to decide whether or not the JSON-LD processor algorithm is 1.1 or not
  * @param propagate   the default propagate term definition
  * @param `protected` the default protected term definition
  * @param language    the default language for all string values
  * @param direction   the default direction for all string values
  */
final case class Context(
    terms: Terms = Map.empty,
    keywords: KeywordAliases = Map.empty,
    prefixMappings: PrefixMappings = Map.empty,
    vocab: EmptyNullOr[Uri] = Empty,
    base: EmptyNullOr[Uri] = Empty,
    version11: Option[Boolean] = None,
    propagate: Option[Boolean] = None,
    `protected`: Option[Boolean] = None,
    language: EmptyNullOr[LanguageTag] = Empty,
    direction: EmptyNullOr[String] = Empty,
    ignoreAncestors: Boolean = false
) {

  private[jsonld] lazy val vocabOpt = vocab.toOption

  /**
    * A reverse index for the keywords aliases. The keys are the aliases, the values are the keywords
    */
  lazy val aliases: Map[String, String] = keywords.flatMap { case (keyword, aliases) => aliases.map(_ -> keyword) }

  private[jsonld] def expandPrefixTerm(prefix: Prefix): Option[Uri] =
    terms.get(prefix.value).flatMap(_.map(_.id)) orElse
      prefixMappings.get(prefix) orElse
      vocabOpt.flatMap(vocab => Uri(vocab.iriString + prefix.value).toOption)

  private[jsonld] def expandValidTerm(str: String): Option[Uri] =
    terms.get(str).flatMap(_.map(_.id)) orElse
      vocabOpt.flatMap(vocab => Uri(vocab.iriString + str).toOption)

  private[jsonld] def expandCurie(curie: Curie): Option[Uri] =
    curie.toIri(prefixMappings).toOption.flatten

  private[jsonld] def expandRelativeTerm(relative: RelativeIri): Option[Uri] =
    base.map(relative.resolve).toOption

  private[jsonld] def validTerm(str: String): Option[String] =
    Option.when(str.nonEmpty && !Prefix.isReserved(str))(str)

  private[jsonld] def expandTerm(
      string: String,
      prefixFn: Prefix => Option[Uri] = expandPrefixTerm,
      termFn: String => Option[Uri] = expandValidTerm,
      curieFn: Curie => Option[Uri] = expandCurie
  ): Either[String, Uri] = {
    lazy val prefix = Prefix(string).toOption
    lazy val term   = validTerm(string)
    lazy val curie  = Curie(string).toOption
    lazy val uri    = Uri(string).toOption
    if (terms.get(string).exists(_.isEmpty)) Left(invalidTerm(string).message)
    else
      (prefix.flatMap(prefixFn) orElse curie.flatMap(curieFn) orElse uri orElse term.flatMap(termFn))
        .toRight(invalidTerm(string).message)
  }

  private[jsonld] def expandTermValue(
      string: String,
      prefixFn: Prefix => Option[Uri] = expandPrefixTerm,
      termFn: String => Option[Uri] = expandValidTerm,
      curieFn: Curie => Option[Uri] = expandCurie,
      relativeIdFn: RelativeIri => Option[Uri] = expandRelativeTerm
  ): Either[String, Uri] = {
    lazy val relative = RelativeIri(string).toOption
    (expandTerm(string, prefixFn, termFn, curieFn).toOption orElse
      relative.flatMap(relativeIdFn) orElse Option.when(string.isEmpty)(base.toOption).flatten)
      .toRight(invalidTerm(string).message)
  }

  def expand(term: String): Either[String, Uri] = expandTerm(term)

  def expandId(idTerm: String): Either[String, Uri] =
    expandTermValue(idTerm, prefixFn = _ => None, termFn = _ => None)

  def expandValue(termValue: String): Either[String, Uri] = expandTermValue(termValue)

  def find(term: String): EmptyNullOr[TermDefinition] =
    terms.get(term) match {
      case Some(None) => Null
      case Some(Some(definition)) => Val(definition)
      case None => Empty
    }

  def find(uriTerm: Uri): Option[TermDefinition] =
    terms.collectFirst { case (_, Some(d)) if d.id == uriTerm => d }

  def findFirst(uriTerms: Seq[Uri]): Option[(Uri, TermDefinition)] =
    uriTerms.toVector.collectFirstSome(uriTerm => find(uriTerm).map(uriTerm -> _))

  /**
    * @return true when ''term'' is an alias for ''keyword'', false otherwise
   **/
  def isAlias(term: String, keyword: String): Boolean =
    term == keyword || aliases.get(term).contains(keyword)

  /**
    * Navigate down the passed ''term'' and merge the term context with the current context if term is found
    */
  def down(term: String): EmptyNullOr[TermDefinition] =
    find(term).map(d => d.withContext(merge(d.context)))

  /**
    * Navigate down the first term with the passed ''uri'' and merge the term context with the current context if term is found
    */
  def down(uri: Uri): EmptyNullOr[TermDefinition] =
    EmptyNullOr(terms.collectFirst { case (_, Some(d)) if d.id == uri => d }).map(d => d.withContext(merge(d.context)))

  /**
    * Merge the current context with the passed context (Empty, Null or Context).
    * If the passed context is null, the null context is returned.
    * If the passed context has a value, merging occurs.
    * If the passed context is empty, the current context is returned.
    */
  def merge(ctx: EmptyNullOr[Context]): EmptyNullOr[Context] =
    ctx.map(merge).onEmpty(Val(this))

  /**
    * Merge the current context with the passed context. If some of the fields are present in both contexts, the passed
    * one will override the current one
    */
  def merge(ctx: Context): Context =
    if (ctx.ignoreAncestors) ctx
    else
      Context(
        terms ++ ctx.terms,
        keywords ++ ctx.keywords.foldLeft(keywords) {
          case (acc, (k, alias)) => acc.updatedWith(k)(v => Some(v.fold(alias)(_ ++ alias)))
        },
        prefixMappings ++ ctx.prefixMappings,
        ctx.vocab.onEmpty(vocab),
        ctx.base.onEmpty(base),
        ctx.version11.orElse(version11),
        ctx.propagate.orElse(propagate),
        ctx.`protected`.orElse(`protected`),
        ctx.language.onEmpty(language),
        ctx.direction.onEmpty(direction)
      )

}

object Context {

  type KeywordAliases = Map[String, Set[String]]
  type PrefixMappings = Map[Prefix, Uri]
  type PrefixAliases  = Map[String, Uri]
  type Terms          = Map[String, Option[TermDefinition]]

  final val keywords: KeywordAliases = all.filterNot(_ == context).map(k => k -> Set(k)).toMap
  final val empty: Context           = Context()

  implicit final def contextDecoder(implicit options: JsonLdOptions): Decoder[Context] =
    Decoder.decodeJson.emap(ContextParser(_))

}
