package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.Monad
import cats.data.EitherT
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode}
import ch.epfl.bluebrain.nexus.rdf.jena.Jena
import ch.epfl.bluebrain.nexus.rdf.jena.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.JenaWriterCleanup.reservedId
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd.IdRetrievalError.{IdNotFound, InvalidId, Unexpected}
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.rdf._
import com.github.jsonldjava.core.JsonLdOptions
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.{JsonLDWriteContext, RDFFormat, RDFWriter}

import scala.util.Try

object JsonLd {

  /**
    * Resolve all IRIs inside @context.
    *
    * @param json     [[Json]] with context to resolve
    * @param resolver context resolver
    * @return [[Json]] with all the context IRIs resolved
    */
  def resolveContext[F[_]: Monad](
      json: Json
  )(resolver: AbsoluteIri => F[Option[Json]]): F[Either[ContextResolutionError, Json]] = {

    def inner(resolvedIds: List[AbsoluteIri], context: Json): EitherT[F, ContextResolutionError, Json] =
      (context.asString, context.asArray, context.asObject) match {
        case (Some(str), _, _) =>
          val nextRef = Iri.absolute(str).toOption
          // format: off
          for {
            next  <- EitherT.fromOption[F](nextRef, IllegalContextValue(str))
            _     <- if (resolvedIds.contains(next)) EitherT.leftT[F, Unit](CircularContextDependency(next :: resolvedIds)) else EitherT.rightT[F, ContextResolutionError](())
            res   <- EitherT.fromOptionF(resolver(next), ContextNotFound(next))
            value <- inner(next :: resolvedIds, contextValue(res))
          } yield value
        // format: on
        case (_, Some(arr), _) =>
          EitherT(arr.traverse(j => inner(resolvedIds, j).value).map {
            _.foldLeft[Either[ContextResolutionError, Json]](Right(Json.obj())) {
              case (Right(accJ), Right(json)) =>
                Right(accJ deepMerge json)
              case (Left(rej), _) => Left(rej)
              case (_, Left(rej)) => Left(rej)
            }
          })

        case (_, _, Some(_)) => EitherT.rightT[F, ContextResolutionError](context)
        case (_, _, _)       => EitherT.leftT[F, Json](IllegalContextValue(context.spaces2): ContextResolutionError)
      }
    inner(List.empty, contextValue(json))
      .map(flattened => replaceContext(json, Json.obj("@context" -> flattened)))
      .value
  }

  /**
    * @return a new Json with the values of all the ''@context'' keys
    */
  def contextValue(json: Json): Json =
    (json.asObject, json.asArray) match {
      case (Some(jObj), _) if jObj.nonEmpty =>
        val context = jObj("@context").getOrElse(Json.obj())
        jObj.remove("@context").values.foldLeft(context)((acc, c) => merge(acc, contextValue(c)))
      case (_, Some(arr)) if arr.nonEmpty =>
        arr.foldLeft(Json.obj())((acc, c) => merge(acc, contextValue(c)))
      case _ =>
        Json.obj()
    }

  /**
    * Replaces the @context value from the provided json to the one in ''that'' json
    *
    * @param json the primary json
    * @param that the json with a @context to override the @context in the provided ''json''
    */
  def replaceContext(json: Json, that: Json): Json =
    removeNestedKeys(json, "@context") deepMerge Json.obj("@context" -> contextValue(that))

  /**
    * Replaces the top @context value from the provided json to the provided ''iri''
    *
    * @param json the primary json
    * @param iri  the iri which overrides the existing json
    */
  def replaceContext(json: Json, iri: AbsoluteIri): Json =
    removeNestedKeys(json, "@context") deepMerge Json.obj("@context" -> Json.fromString(iri.asString))

  /**
    * Removes the provided keys from everywhere on the json.
    *
    * @param json the json
    * @param keys list of ''keys'' to be removed from the top level of the ''json''
    * @return the original json without the provided ''keys''
    */
  def removeNestedKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        obj.filterKeys(!keys.contains(_)).toVector.map { case (k, v) => k -> removeNestedKeys(v, keys: _*) }
      )
    json.arrayOrObject[Json](
      json,
      arr => Json.fromValues(removeEmpty(arr.map(j => removeNestedKeys(j, keys: _*)))),
      obj => inner(obj).asJson
    )
  }

  /**
    * Adds or merges a context URI to an existing JSON object.
    *
    * @param json    the json
    * @param context the standard context URI
    * @return a new JSON object
    */
  def addContext(json: Json, context: AbsoluteIri): Json = {
    val contextUriString = Json.fromString(context.asString)

    json.asObject match {
      case Some(jo) =>
        val updated = jo("@context") match {
          case None => jo.add("@context", contextUriString)
          case Some(value) =>
            (value.asObject, value.asArray, value.asString) match {
              case (Some(vo), _, _) if vo.isEmpty =>
                jo.add("@context", contextUriString)
              case (_, Some(va), _) if va.isEmpty =>
                jo.add("@context", contextUriString)
              case (_, _, Some(vs)) if vs.isEmpty =>
                jo.add("@context", contextUriString)
              case (Some(vo), _, _) if !vo.values.exists(_ == contextUriString) =>
                jo.add("@context", Json.arr(value, contextUriString))
              case (_, Some(va), _) if !va.contains(contextUriString) =>
                jo.add("@context", Json.fromValues(va :+ contextUriString))
              case (_, _, Some(vs)) if vs != context.asString =>
                jo.add("@context", Json.arr(value, contextUriString))
              case _ => jo
            }
        }
        Json.fromJsonObject(updated)
      case None => json
    }
  }

  /**
    * @param json the primary context. E.g.: {"@context": {...}}
    * @param that the other context to merge with this context. E.g.: {"@context": {...}}
    * @return a new Json with the values of the ''@context'' key (this) and the provided ''that'' top ''@context'' key
    *         If two keys inside both contexts collide, the one in the ''other'' context will override the one in this context
    */
  def mergeContext(json: Json, that: Json): Json =
    Json.obj("@context" -> merge(contextValue(json), contextValue(that)))

  /**
    * @param json the primary context
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original context plus the provided context
    */
  def appendContextOf(json: Json, that: Json): Json = json deepMerge mergeContext(json, that)

  /**
    * Adds @id value to the provided Json
    *
    * @param json  the json
    * @param value the @id value
    */
  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def id(json: Json, value: AbsoluteIri): Json =
    (json.asObject, json.asArray) match {
      case (Some(_), _)                      => json deepMerge Json.obj("@id" -> Json.fromString(value.asString))
      case (_, Some(jArr)) if jArr.size == 1 => Json.arr(id(jArr.head, value))
      case _                                 => json
    }

  /**
    * Attempts to find the top `@id` value on the provided json.
    *
    * @param json the json
    * @return Right(iri) of found, Left(error) otherwise
    */
  def id(json: Json): IdOrError =
    Jena.parse(json.noSpaces).left.map(err => Unexpected(err)).flatMap { m =>
      val aliases = contextAliases(json, "@id") + "@id"
      val baseOpt = contextValue(json).hcursor.get[String]("@base").flatMap(Iri.absolute).toOption

      def singleGraph(value: JsonObject): Option[JsonObject] =
        value("@graph").flatMap { json =>
          (json.asObject, json.asArray) match {
            case (Some(jObj), _)                   => Some(jObj)
            case (_, Some(jArr)) if jArr.size == 1 => jArr.head.asObject
            case _                                 => None
          }
        }

      def buildWithPrefix(id: String): IdOrError =
        Iri.absolute(m.expandPrefix(id)).left.map(_ => InvalidId(id))

      def buildWithBase(id: String): IdOrError =
        baseOpt.flatMap(base => Iri.absolute(s"${base.asString}$id").toOption).toRight(InvalidId(id))

      def idCandidates(value: JsonObject): Set[String] =
        aliases.foldLeft(Set.empty[String]) {
          case (ids, alias) => ids ++ value(alias).flatMap(_.asString)
        }

      def collectOne(ids: Set[String], f: String => IdOrError): IdOrError =
        ids.foldLeft[Either[IdRetrievalError, AbsoluteIri]](Left(IdNotFound)) {
          case (Right(id), _) => Right(id)
          case (_, id)        => f(id)
        }

      def inner(value: JsonObject): IdOrError =
        idCandidates(value) ++ singleGraph(value).map(idCandidates).getOrElse(Set.empty) match {
          case ids if ids.isEmpty => Left(IdRetrievalError.IdNotFound)
          case ids                => collectOne(ids, buildWithPrefix).left.flatMap(_ => collectOne(ids, buildWithBase))
        }

      (json.asObject, json.asArray) match {
        case (Some(jObj), _)                 => inner(jObj)
        case (_, Some(arr)) if arr.size == 1 => arr.head.asObject.toRight(IdNotFound).flatMap(inner)
        case _                               => Left(IdNotFound)
      }
    }

  /**
    * Removes the provided keys from the top object on the json.
    *
    * @param json the json
    * @param keys list of ''keys'' to be removed from the top level of the ''json''
    * @return the original json without the provided ''keys'' on the top level of the structure
    */
  def removeKeys(json: Json, keys: String*): Json = {
    def inner(obj: JsonObject): JsonObject = obj.filterKeys(!keys.contains(_))
    json.arrayOrObject[Json](
      json,
      arr => Json.fromValues(removeEmpty(arr.map(j => removeKeys(j, keys: _*)))),
      obj => inner(obj).asJson
    )
  }

  /**
    * Retrieves the aliases on the provided ''json'' @context for the provided ''keyword''
    *
    * @param json    the Json-LD
    * @param keyword the Json-LD keyword. E.g.: @id, @type, @container, @set
    * @return a set of aliases found for the given keyword
    */
  def contextAliases(json: Json, keyword: String): Set[String] = {
    val jsonKeyword = Json.fromString(keyword)
    def inner(ctx: Json): Set[String] =
      (ctx.asObject, ctx.asArray) match {
        case (Some(jObj), _) =>
          jObj.toMap.collect { case (k, `jsonKeyword`) => k }.toSet
        case (_, Some(jArr)) =>
          jArr.foldLeft(Set.empty[String])(_ ++ inner(_))
        case _ => Set.empty
      }

    inner(contextValue(json))
  }

  /**
    * Create and instance of [[GraphDecoder]] to decode [[Graph]] to [[Json]]
    *
    * @param  context the context to apply
    * @return [[GraphDecoder]] instance
    */
  def toJsonDecoder(context: Json): GraphDecoder[Json] =
    GraphDecoder.instance { c =>
      toJson(c.graph, context).leftMap(str => DecodingError(str, c.top.history))
    }

  /**
    * Convert [[Graph]] to [[Json]] by applying provided context.
    *
    * @param graph   [[Graph]] to convert to [[Json]]
    * @param context the context to apply
    * @return [[Json]] representation of the graph or error message
    */
  def toJson(graph: Graph, context: Json = Json.obj()): Either[String, Json] = {
    val jenaCleanup: JenaWriterCleanup = new JenaWriterCleanup(context)
    val justContextObj = {
      val value = context.hcursor.downField("@context").as[Json].getOrElse(Json.obj())
      if (value == Json.obj() || value == Json.arr()) Json.obj()
      else Json.obj("@context" -> value)
    }

    def writeFramed(id: AbsoluteIri): Either[String, Json] = {
      val opts = new JsonLdOptions()
      opts.setEmbed(true)
      opts.setProcessingMode(JsonLdOptions.JSON_LD_1_1)
      opts.setCompactArrays(true)
      opts.setPruneBlankNodeIdentifiers(true)
      val frame = Json.obj("@id" -> Json.fromString(id.asUri)).appendContextOf(jenaCleanup.cleanFromCtx)
      val ctx   = new JsonLDWriteContext
      ctx.setFrame(frame.noSpaces)
      ctx.setOptions(opts)
      val jenaModel = graph.asJena
      Try {
        val g = DatasetFactory.wrap(jenaModel).asDatasetGraph
        RDFWriter.create().format(RDFFormat.JSONLD_FRAME_FLAT).source(g).context(ctx).build().asString()
      }.toEither match {
        case Right(jsonString) =>
          val parsedOrErr = parse(jsonString).left.map(_.message)
          parsedOrErr
            .map(jenaCleanup.removeSingleGraph)
            .map(jenaCleanup.cleanFromJson(_, graph))
            .map(_ deepMerge justContextObj)
        case Left(message) => Left(s"error while writing Json-LD. Reason '$message'")
      }
    }

    if (graph.triples.isEmpty)
      Right(graph.root match {
        case IriNode(value) => Json.obj("@id" -> Json.fromString(value.asUri))
        case _              => Json.obj()
      })
    else
      graph.root match {
        case IriNode(`reservedId`) => writeFramed(reservedId).map(removeKeys(_, "@id"))
        case IriNode(value)        => writeFramed(value)
        case blank: BNode          => toJson(graph.replaceNode(blank, reservedId), context)
        case _                     => toJson(graph.withRoot(reservedId), context)
      }
  }

  /**
    * Convert [[Json]] object to graph
    * @param json [[Json]] to convert
    * @param node [[Node]] to use as root node of the [[Graph]]
    * @return [[Graph]] representation of this [[Json]] or error message.
    */
  def toGraph(json: Json, node: Node): Either[String, Graph] = Jena.parse(json.noSpaces).flatMap(_.asRdfGraph(node))

  private def removeEmpty(arr: Seq[Json]): Seq[Json] =
    arr.filter(j => j != Json.obj() && j != Json.fromString("") && j != Json.arr())

  private def merge(json: Json, that: Json): Json =
    (json.asArray, that.asArray, json.asString, that.asString) match {
      case (Some(arr), Some(thatArr), _, _) => Json.arr(removeEmpty(arr ++ thatArr): _*)
      case (_, Some(thatArr), _, _)         => Json.arr(removeEmpty(json +: thatArr): _*)
      case (Some(arr), _, _, _)             => Json.arr(removeEmpty(arr :+ that): _*)
      case (_, _, Some(str), Some(thatStr)) => Json.arr(removeEmpty(Seq(str.asJson, thatStr.asJson)): _*)
      case (_, _, Some(str), _)             => Json.arr(removeEmpty(Seq(str.asJson, that)): _*)
      case (_, _, _, Some(thatStr))         => Json.arr(removeEmpty(Seq(json, thatStr.asJson)): _*)
      case _                                => json deepMerge that
    }

  /**
    * Exception signalling an error during context resolution.
    * @param msg error message
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  sealed abstract class ContextResolutionError(val msg: String) extends Exception with Product with Serializable {
    override def fillInStackTrace(): ContextResolutionError = this
    // $COVERAGE-OFF$
    override def getMessage: String = msg
    // $COVERAGE-ON$
  }

  /**
    * Exception signalling circular context dependency.
    * @param ids list of context dependencies
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class CircularContextDependency(ids: List[AbsoluteIri])
      extends ContextResolutionError(
        s"Context dependency graph '${ids.reverseIterator.map(_.show).to(List).mkString(" -> ")}' contains a cycle"
      )

  /**
    * Exception signalling illegal context value.
    * @param context the illegal value
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class IllegalContextValue(context: String)
      extends ContextResolutionError(s"'$context' is not a valid @context value")

  /**
    * Exception signalling that a context could not be resolved.
    * @param id context ID
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class ContextNotFound(id: AbsoluteIri)
      extends ContextResolutionError(s"Context ${id.show} could not be resolved.")

  /**
    * Enumeration types for errors when fetching Ids
    */
  sealed trait IdRetrievalError extends Product with Serializable
  object IdRetrievalError {

    /**
      * The id is invalid
      *
      * @param id the id value
      */
    final case class InvalidId(id: String) extends IdRetrievalError

    /**
      * Unexpected error
      *
      * @param cause human readable cause
      */
    final case class Unexpected(cause: String) extends IdRetrievalError

    /**
      * There is no Id to be extracted
      */
    final case object IdNotFound extends IdRetrievalError
  }

  private type IdOrError = Either[IdRetrievalError, AbsoluteIri]

}
