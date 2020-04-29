package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.iri.Curie
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix
import ch.epfl.bluebrain.nexus.rdf.iri.Curie.Prefix.isReserved
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.{RelativeIri, Uri}
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, EmptyNullOr, JsonLdOptions}
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.{Empty, Null, Val}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.Context
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.Context._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri.{KeywordValue, UriValue}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition._
import ch.epfl.bluebrain.nexus.rdf.jsonld.instances.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword.{none, _}
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser.TermDef._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{Decoder, DecodingFailure, Json, JsonObject}

private[jsonld] object ContextParser {
  private type _Terms        = Map[String, TermDef]
  private type ResolvedTerms = Map[String, Uri]

  private val genDelim = Set(":", "/", "?", "#", "[", "]", "@")

  private def isGenDelim(uri: Uri): Boolean = uri.isUrl && genDelim.contains(uri.iriString.takeRight(1))

  sealed trait TermDef extends Product with Serializable

  object TermDef {

    final private[ContextParser] case class SimpleDef(value: String, isString: Boolean) extends TermDef

    final private[ContextParser] case object NullDef extends TermDef

    final private[ContextParser] case class ExpandedDef(
        id: Option[String] = None,
        tpe: Option[String] = None,
        container: Option[Set[String]] = None,
        language: EmptyNullOr[LanguageTag] = Empty,
        prefix: Option[Boolean] = None,
        `protected`: Option[Boolean] = None,
        propagate: Option[Boolean] = None,
        direction: EmptyNullOr[String] = Empty,
        reverse: Option[String] = None,
        `import`: Option[String] = None,
        index: Option[String] = None,
        context: EmptyNullOr[Set[EmptyNullOr[ActiveContext]]] = Empty,
        nest: Option[String] = None
    ) extends TermDef

    implicit val config: Configuration =
      Configuration.default.copy(transformMemberNames = {
        case "tpe" => `tpe`
        case other => "@" + other
      })

    implicit val termDefDecoder: Decoder[TermDef] = Decoder.instance { hc =>
      val expDec = deriveConfiguredDecoder[ExpandedDef]
        .ensure(
          d => d.container.forall(_.subsetOf(allowedContainerKeys)),
          s"$container invalid keyword. Allowed keywords: ${allowedContainerKeys.mkString(",")}"
        )
        .ensure(
          d => !d.container.contains(Set(list, set)),
          s"$container cannot contain $set and $list keywords at the same time"
        )
        .ensure(
          d => d.reverse.forall(_ => d.id.isEmpty && d.nest.isEmpty && d.container.forall(_.subsetOf(Set(index, set)))),
          s"$reverse cannot be present at the same time as $id or $nest or container with keywords different than $index or $set"
        )
        .ensure(
          d => d.index.forall(_ => d.container.exists(_.contains(index))),
          s"$index keyword requires $container $index to be present"
        )
        .ensure(
          d => d.index.forall(!all.contains(_)),
          s"$index value must not be a keyword"
        )
        .ensure(d => d.direction.forall(allowedDirection.contains), invalidDirection.message)
        .ensure(
          d => d.nest.forall(nest => !forbiddenNestKeys.contains(nest)),
          s"$nest invalid keyword. Allowed keywords: $nest"
        )
      (hc.value.asObject, hc.value.asString, hc.value.asNumber, hc.value.asBoolean, hc.value.asNull) match {
        case (Some(obj), _, _, _, _) if obj == JsonObject(keyword.id -> Json.Null) => Right(NullDef)
        case (Some(_), _, _, _, _)      => expDec(hc)
        case (_, Some(value), _, _, _)  => Right(SimpleDef(value, isString = true))
        case (_, _, Some(number), _, _) => Right(SimpleDef(number.toDouble.toString, isString = false))
        case (_, _, _, Some(bool), _)   => Right(SimpleDef(bool.toString, isString = false))
        case (_, _, _, _, Some(_))      => Right(NullDef)
        case _                          => Left(DecodingFailure("A term definition must be a string or a Json Object", hc.history))
      }
    }
  }

  final private case class ActiveContext(
      terms: _Terms,
      allTerms: _Terms,
      resolved: ResolvedTerms,
      ctx: Context,
      options: JsonLdOptions
  ) {

    /**
      * Fetch the prefix mappings and resolved Uris. It also removed the reserved terms (terms with regex "@"1*ALPHA)
      */
    private def prefixMappingsAndResolvedUris(): ActiveContext =
      terms.foldLeft(this) {
        case (acc, (term, _)) if !all.contains(term) && isReserved(term) =>
          acc.removeTerm(term)
        case (acc, (term, SimpleDef(value, _))) if !all.contains(value) && isReserved(value) =>
          acc.removeTerm(term)
        case (acc, (term, d: ExpandedDef)) if d.id.exists(id => !all.contains(id) && isReserved(id)) =>
          acc.removeTerm(term)
        case (acc, (term, d: ExpandedDef)) if d.reverse.exists(r => !all.contains(r) && isReserved(r)) =>
          acc.removeTerm(term)

        case (acc, (term, SimpleDef(value, _))) =>
          Prefix(term).flatMap(p => acc.curieOrUri(value).map(acc.addTermOrPm(p, _))).getOrElse(acc)

        case (acc, (term, ExpandedDef(Some(value), _, _, _, Some(prefixKey), _, _, _, _, _, _, _, _))) =>
          (Prefix(term), Uri(value))
            .mapN {
              case (prefix, uri) if prefixKey || isGenDelim(uri) => acc.addPm(prefix, uri)
              case (prefix, uri)                                 => acc.addResolved(prefix, uri)
            }
            .getOrElse(acc)

        case (acc, _) => acc
      }

    private def initializeBase(): Either[String, ActiveContext] =
      terms.get(`base`) match {
        case Some(SimpleDef(str, true)) => prefixCurieUriOrRelative(str).map(withBase)
        case Some(NullDef)              => Right(copy(ctx = ctx.copy(base = Null)))
        case Some(_)                    => Left(s"$base must be a String")
        case None                       => Right(options.base.map(withBase).getOrElse(this))
      }

    /**
      * Initialize the active context with the top level default keywords, if present
      */
    private def initializeKeywords(): Either[String, ActiveContext] =
      terms
        .removed(`base`)
        .toList
        .foldM(this) {
          case (acc, (`version`, SimpleDef("1.1", false))) => Right(acc.withVersion11)
          case (_, (`version`, _))                         => Left(s"$version keyword value must be '1.1'")
          case (acc, (`vocab`, SimpleDef("", true))) =>
            ctx.base.toOption.toRight(invalidTerm("").message).map(acc.withVocab)
          case (acc, (`vocab`, SimpleDef(str, true)))      => acc.resolveVocab(str).map(acc.withVocab)
          case (acc, (`vocab`, NullDef))                   => Right(acc.copy(ctx = acc.ctx.copy(vocab = Null)))
          case (_, (`vocab`, SimpleDef(_, false)))         => Left(s"$language must be a String")
          case (acc, (`propagate`, SimpleDef(str, false))) => toBool(str).map(acc.withPropagate)
          case (_, (`propagate`, SimpleDef(_, true)))      => Left(s"$propagate must be a boolean")
          case (acc, (`protected`, SimpleDef(str, false))) => toBool(str).map(acc.withProtected)
          case (_, (`protected`, SimpleDef(_, true)))      => Left(s"${`protected`} must be a boolean")
          case (acc, (`language`, SimpleDef(str, true)))   => LanguageTag(str).map(acc.withLanguage)
          case (acc, (`language`, NullDef))                => Right(acc.withNullLanguage)
          case (_, (`language`, SimpleDef(_, false)))      => Left(s"$language must be a String")
          case (acc, (`direction`, SimpleDef(str, true))) if allowedDirection.contains(str) =>
            Right(acc.withDirection(str))
          case (acc, (`direction`, NullDef))           => Right(acc.withNullDirection)
          case (_, (`direction`, SimpleDef(_, false))) => Left(s"$direction must be a String")
          case (_, (`direction`, _)) =>
            Left(s"$direction invalid value. Allowed values: ${allowedDirection.mkString(",")}")
          case (_, (term, SimpleDef(_, false)))     => Left(s"Term '$term' value must be a String or an object")
          case (_, (term, _)) if all.contains(term) => Left(s"Keyword '$term' cannot be used in term position")
          case (acc, _)                             => Right(acc)
        }
        .map(acc => acc.copy(terms = acc.terms -- allowedTermKeys))

    /**
      * Fetch the keywords aliases.
      */
    private def aliases(): Either[String, ActiveContext] =
      terms.toList.foldM(this) {
        case (acc, (alias, SimpleDef(keyword, _))) if keywords.contains(keyword) =>
          Right(acc.removeTerm(alias).addAlias(keyword, alias))
        case (acc, (alias, SimpleDef(aliasedKey, _))) if acc.ctx.aliases.contains(aliasedKey) =>
          Right(acc.removeTerm(alias).addAlias(acc.ctx.aliases(aliasedKey), alias))
        case (_, (term, d: ExpandedDef)) if d.id.contains(context) =>
          Left(s"$context cannot be aliased with term '$term'")
        case (acc, (alias, ExpandedDef(Some(k), _, c, Empty, None, _, None, Empty, None, None, None, Empty, None)))
            if keywords.contains(k) && c.forall(_ == Set(set)) =>
          Right(acc.addAlias(k, alias))
        case (acc, _) => Right(acc)
      }

    /**
      * Resolve the context values @type, @reverse, @id, @index and term prefixes and curies
      */
    private def resolveUris(): Either[String, ActiveContext] = {
      terms.toList.foldM(this) {
        case (acc, (term, SimpleDef(value, _))) =>
          acc.prefixCurieOrUri(value).flatMap { uriDef =>
            Prefix(term).map(prefix => acc.addTermOrPm(prefix, uriDef)) orElse
              acc.prefixCurieOrUri(term).flatMap { termUri =>
                Option
                  .when(termUri == uriDef)(acc.addTerm(term, uriDef))
                  .toRight(s"'$term' and definition '$value' must expand to the same Uri")
              }
          }
        case (acc, (term, NullDef)) =>
          Right(acc.addNullTerm(term))
        case (acc, (term, d: ExpandedDef)) =>
          for {
            typeKeywordOrUri <- acc.resolveType(d)
            reverseUri       <- acc.resolveReverse(d)
            indexUri         <- acc.resolveIndex(d)
            termAndIdUri     <- acc.resolveTermAndId(term, d, reverseUri)
            (prefix, idUri)  = termAndIdUri
            resolvedDefinition = ExpandedTermDefinition(
              idUri,
              tpe = typeKeywordOrUri,
              container = d.container.getOrElse(Set.empty),
              language = d.language,
              prefix = d.prefix.getOrElse(false),
              `protected` = d.`protected`,
              propagate = d.propagate,
              direction = d.direction,
              reverse = reverseUri,
              index = indexUri,
              nest = d.nest
            )
          } yield prefix.fold(acc.addTerm(term, resolvedDefinition))(acc.addTermOrPm(_, resolvedDefinition))
      }
    }

    private def merge(other: EmptyNullOr[ActiveContext]): EmptyNullOr[ActiveContext] =
      other
        .map(otherCtx => copy(terms = terms ++ otherCtx.terms, allTerms = allTerms ++ otherCtx.allTerms))
        .onEmpty(Val(this))

    /**
      * Resolve the scoped contexts within term definitions
      */
    private def resolveScopedCtx(): Either[String, ActiveContext] = {
      allTerms.toList.foldM(this) {
        case (acc, (term, d: ExpandedDef)) if ctx.terms.contains(term) =>
          val terms           = allTerms - term
          val ignoreAncestors = d.context.isNull || d.context.map(_.exists(_.isNull)).toOption.getOrElse(false)
          val initial: EmptyNullOr[ActiveContext] =
            Val(ActiveContext(terms, terms, resolved, Context.empty, options))
          val mergedScoped = d.context
            .flatMap { ctx => ctx.foldLeft(initial)((acc, c) => acc.flatMap(_.merge(c)).onEmptyOrNull(c)) }
            .traverse(v => v.toResolvedContext)
          mergedScoped.map { mergedScopedResult =>
            val finalCtx =
              removeInherited(mergedScopedResult.map(_.ctx), d.context).map(_.copy(ignoreAncestors = ignoreAncestors))
            val updatedTerm = ctx.terms(term).map(_.withContext(finalCtx))
            acc.copy(ctx = acc.ctx.copy(terms = acc.ctx.terms + (term -> updatedTerm)))
          }
        case (acc, _) => Right(acc)
      }
    }

    /**
      * Apply the Context algorithm from the Json-LD 1.1 spec. to resolve the ActiveContext
      */
    def toResolvedContext: Either[String, ActiveContext] =
      prefixMappingsAndResolvedUris()
        .initializeBase()
        .flatMap(_.initializeKeywords())
        .flatMap(_.aliases())
        .flatMap(_.resolveUris())
        .flatMap(_.resolveScopedCtx())

    private def removeTerm(term: String): ActiveContext =
      copy(terms = terms - term)

    private def addAlias(keyword: String, alias: String): ActiveContext = {
      val keywordUpdated = ctx.keywords.updatedWith(keyword)(v => Some(v.fold(Set(alias))(_ + alias)))
      copy(ctx = ctx.copy(keywords = keywordUpdated), terms = terms - alias)
    }

    private def withVersion11: ActiveContext =
      copy(ctx = ctx.copy(version11 = Some(true)))

    private def withVocab(value: Uri): ActiveContext =
      copy(ctx = ctx.copy(vocab = Val(value)))

    private def withBase(value: Uri): ActiveContext =
      copy(ctx = ctx.copy(base = Val(value)))

    private def withProtected(value: Boolean): ActiveContext =
      copy(ctx = ctx.copy(`protected` = Some(value)))

    private def withLanguage(value: LanguageTag): ActiveContext =
      copy(ctx = ctx.copy(language = Val(value)))

    private def withNullLanguage: ActiveContext =
      copy(ctx = ctx.copy(language = Null))

    private def withPropagate(value: Boolean): ActiveContext =
      copy(ctx = ctx.copy(propagate = Some(value)))

    private def withDirection(value: String): ActiveContext =
      copy(ctx = ctx.copy(direction = Val(value)))

    private def withNullDirection: ActiveContext =
      copy(ctx = ctx.copy(direction = Null))

    private[parser] def addTerm(term: String, definition: ExpandedTermDefinition): ActiveContext =
      removeTerm(term).copy(ctx = ctx.copy(terms = ctx.terms + (term -> Some(definition))))

    private def addTerm(term: String, uri: Uri): ActiveContext =
      removeTerm(term).copy(ctx = ctx.copy(terms = ctx.terms + (term -> Some(uri))))

    private def addNullTerm(term: String): ActiveContext =
      removeTerm(term).copy(ctx = ctx.copy(terms = ctx.terms + (term -> None)))

    private def addTermOrPm(prefix: Prefix, definition: ExpandedTermDefinition): ActiveContext =
      if (isGenDelim(definition.id) || definition.prefix)
        addPm(prefix, definition.id).addTerm(prefix.value, definition)
      else
        addTerm(prefix.value, definition)

    private def addTermOrPm(prefix: Prefix, expanded: Uri): ActiveContext =
      if (isGenDelim(expanded))
        removeTerm(prefix.value).addPm(prefix, expanded)
      else
        addTerm(prefix.value, expanded)

    private def addPm(prefix: Prefix, expanded: Uri): ActiveContext =
      copy(ctx = ctx.copy(prefixMappings = ctx.prefixMappings + (prefix -> expanded)))

    private def addResolved(prefix: Prefix, expanded: Uri): ActiveContext =
      copy(resolved = resolved + (prefix.value -> expanded))

    private def expandTerm(prefix: Prefix): Option[Uri] =
      resolved.get(prefix.value) orElse
        ctx.expandPrefixTerm(prefix) orElse
        ctx.vocabOpt.flatMap(vocab => Uri(vocab.iriString + prefix.value).toOption)

    private def curieOrUri(term: String): Either[String, Uri] =
      (Curie(term).toOption.flatMap(ctx.expandCurie) orElse Uri(term).toOption)
        .toRight(s"Term '$term' couldn't be expanded to a Uri")

    private def prefixCurieOrUri(term: String): Either[String, Uri] = ctx.expandTerm(term, prefixFn = expandTerm)

    private def resolveVocab(term: String): Either[String, Uri] =
      ctx.expandTermValue(term, prefixFn = expandTerm, relativeIdFn = r => ctx.base.map(r.resolve).toOption)

    private def prefixCurieUriOrRelative(str: String): Either[String, Uri] =
      (Option.when(str.isEmpty)(options.base).flatten orElse prefixCurieOrUri(str).toOption orElse
        (RelativeIri(str).toOption, options.base).mapN { case (rel, b) => rel.resolve(b) })
        .toRight(invalidTerm(str).message)

    private def resolveType(definition: ExpandedDef): Either[String, Option[KeywordOrUri]] =
      definition.tpe
        .traverse {
          case v if definition.container.exists(_.contains(tpe)) && !allowedTypesOnContainerType.contains(v) =>
            Left(s"$tpe with value '$v' cannot be present at the same time as $container with $tpe")
          case v if allowedTypeKeys.contains(v) => Right(KeywordValue(v))
          case v if all.contains(v)             => Left(s"Keyword '$v' cannot be used as the $tpe value")
          case v                                => prefixCurieOrUri(v).map(UriValue)
        }
        .map {
          case None if definition.container.exists(_.contains(tpe)) => Some(KeywordValue(id))
          case other                                                => other
        }

    private def resolveReverse(definition: ExpandedDef): Either[String, Option[Uri]] =
      definition.reverse.filterNot(all.contains).traverse(prefixCurieOrUri)

    private def resolveIndex(definition: ExpandedDef): Either[String, Option[Uri]] =
      definition.index.traverse(prefixCurieOrUri)

    private def resolveTermAndId(
        term: String,
        definition: ExpandedDef,
        reverseUri: Option[Uri]
    ): Either[String, (Option[Prefix], Uri)] = {
      lazy val err = s"term '$term' must have an $id that resolves to a Uri"
      def idsCheck(
          idUri: => Option[Uri],
          termCurieOrUri: => Option[Uri],
          termPrefix: => Option[Uri],
          validTerm: => Option[Uri]
      ) =
        (idUri, termCurieOrUri, termPrefix) match {
          case (Some(id), Some(termOrCurie), _) if id != termOrCurie => Left(s"'$id' must equal '$termOrCurie'")
          case _ =>
            (idUri orElse termCurieOrUri orElse termPrefix).toRight(err) orElse
              validTerm.toRight(err).flatMap { uri =>
                Option
                  .when(!(RelativeIri(term).isRight && definition.prefix.exists(_ == true)))(uri)
                  .toRight(s"relative term '$term' cannot be used in combination of @prefix with value 'true'")
              }
        }

      for {
        uriDefinition <- if (definition.reverse.nonEmpty) Right(reverseUri)
                        else definition.id.filterNot(keywords.contains).traverse(prefixCurieOrUri)
        prefix    <- Right(Prefix(term).toOption)
        validTerm <- Right(ctx.validTerm(term))
        uri <- idsCheck(
                uriDefinition,
                curieOrUri(term).toOption,
                prefix.flatMap(expandTerm),
                validTerm.flatMap(ctx.expandValidTerm)
              )
      } yield (prefix, uri)
    }
  }

  private object ActiveContext {
    final def apply(terms: _Terms)(implicit options: JsonLdOptions): ActiveContext =
      ActiveContext(terms, terms, Map.empty, Context.empty, options)
  }

  // TODO: Not yet implemented for keywords
  private[parser] def removeInherited(
      merged: EmptyNullOr[Context],
      original: EmptyNullOr[Set[EmptyNullOr[ActiveContext]]]
  ): EmptyNullOr[Context] =
    (merged, original).mapN { case (mergedCtx, origCtx) => removeInherited(mergedCtx, origCtx) }

  private def removeInherited(context: Context, original: Set[EmptyNullOr[ActiveContext]]): Context =
    Context(
      terms = context.terms.view.filterKeys(k => original.exists(_.exists(_.terms.contains(k)))).toMap,
      prefixMappings =
        context.prefixMappings.view.filterKeys(k => original.exists(_.exists(_.terms.contains(k.value)))).toMap,
      keywords = context.keywords,
      vocab = if (original.exists(_.exists(_.terms.contains(vocab)))) context.vocab else Empty,
      base = if (original.exists(_.exists(_.terms.contains(base)))) context.base else Empty,
      version11 = if (original.exists(_.exists(_.terms.contains(version)))) context.version11 else None,
      propagate = if (original.exists(_.exists(_.terms.contains(propagate)))) context.propagate else None,
      `protected` = if (original.exists(_.exists(_.terms.contains(`protected`)))) context.`protected` else None,
      language = if (original.exists(_.exists(_.terms.contains(language)))) context.language else Empty,
      direction = if (original.exists(_.exists(_.terms.contains(direction)))) context.direction else Empty
    )

  private def toBool(str: String): Either[String, Boolean] =
    str.toBooleanOption.toRight(s"Expected boolean, found '$str'")

  private[parser] val allowedTypeKeys             = Set(id, json, none, vocab)
  private[parser] val allowedTermKeys             = Set(version, tpe, vocab, base, propagate, `protected`, language, direction)
  private[parser] val allowedContainerKeys        = Set(list, set, language, index, id, tpe, graph)
  private[parser] val allowedTypesOnContainerType = Set(vocab, id)
  private[parser] val forbiddenNestKeys           = all.filterNot(_ == nest)
  private[jsonld] val allowedDirection            = Set("ltr", "rtl")

  private def terms(jObj: JsonObject): Either[String, Map[String, TermDef]] =
    jObj.toList.foldM(Map.empty[String, TermDef]) {
      case (_, ("", _))        => Left("Term for definition cannot be empty")
      case (acc, (term, json)) => json.as[TermDef].map(definition => acc + (term -> definition)).leftMap(_.message)
    }

  private def activeCtx(json: Json)(implicit option: JsonLdOptions): Either[String, ActiveContext] =
    json.arrayOrObject[Either[String, ActiveContext]](
      Left(s"$context should be a Json Array or a Json Object"),
      _.foldM(JsonObject.empty) { (acc, c) =>
        c.asObject.map(deepMerge(acc, _)).toRight(s"$context Array Element must be a Json Object")
      }.flatMap(terms).map(ActiveContext(_)),
      terms(_).map(ActiveContext(_))
    )

  implicit private[ContextParser] def activeContextDecoder(
      implicit option: JsonLdOptions = JsonLdOptions.empty
  ): Decoder[ActiveContext] =
    Decoder.decodeJson.emap(activeCtx)

  private def deepMerge(one: JsonObject, other: JsonObject): JsonObject =
    other.toIterable.foldLeft(one) { case (acc, (k, v)) => acc.add(k, v) }

  def apply(json: Json)(implicit option: JsonLdOptions): Either[String, Context] =
    activeCtx(json).flatMap(_.toResolvedContext).map(_.ctx)

  def apply(json: Json, parent: EmptyNullOr[Context])(implicit option: JsonLdOptions): Either[String, Context] =
    activeCtx(json)
      .flatMap(ctx =>
        ctx
          .copy(ctx = parent.toOption.getOrElse(Context.empty))
          .toResolvedContext
          .map(resolved => removeInherited(resolved.ctx, Set(Val(ctx))))
      )

}
