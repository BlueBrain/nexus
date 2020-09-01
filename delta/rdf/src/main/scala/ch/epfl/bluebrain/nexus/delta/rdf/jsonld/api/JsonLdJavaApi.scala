package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api
import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.IOErrorOr
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdError._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import com.github.jsonldjava.core.{Context, DocumentLoader, JsonLdProcessor, JsonLdOptions => JsonLdJavaOptions}
import com.github.jsonldjava.utils.JsonUtils
import io.circe.{parser, Json}
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{Lang, RDFParser, RDFWriter}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Json-LD high level API implementation by Json-LD Java library
  */
object JsonLdJavaApi extends JsonLdApi {

  System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

  // $COVERAGE-OFF$
  override def compact[Ctx <: JsonLdContext](input: Json, ctx: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[(Json, Ctx)] =
    for {
      obj           <- catchTryErr(JsonUtils.fromString(input.noSpaces), "building input")
      ctxObj        <- catchTryErr(JsonUtils.fromString(ctx.noSpaces), "building context")
      options       <- remoteContextLoader(input, ctx).map(toOpts)
      compacted     <- catchTryErr(JsonUtils.toString(JsonLdProcessor.compact(obj, ctxObj, options)), "compacting")
      compactedJson <- IO.fromEither(parser.parse(compacted).leftMap(err => UnexpectedJsonLd(err.getMessage())))
      ctxValue      <- context(ctx, f, options)
    } yield (compactedJson, ctxValue)
  // $COVERAGE-ON$

  override def expand(
      input: Json
  )(implicit options: JsonLdOptions, resolution: RemoteContextResolution): IOErrorOr[Json] =
    for {
      obj          <- catchTryErr(JsonUtils.fromString(input.noSpaces), "building input")
      options      <- remoteContextLoader(input).map(toOpts)
      expanded     <- catchTryErr(JsonUtils.toString(JsonLdProcessor.expand(obj, options)), "expanding")
      expandedJson <- IO.fromEither(parser.parse(expanded).leftMap(err => UnexpectedJsonLd(err.getMessage())))
    } yield expandedJson

  override def frame[Ctx <: JsonLdContext](input: Json, frame: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[(Json, Ctx)] =
    for {
      obj        <- catchTryErr(JsonUtils.fromString(input.noSpaces), "building input")
      ff         <- catchTryErr(JsonUtils.fromString(frame.noSpaces), "building frame")
      options    <- remoteContextLoader(input, frame).map(toOpts)
      framed     <- catchTryErr(JsonUtils.toString(JsonLdProcessor.frame(obj, ff, options)), "framing")
      framedJson <- IO.fromEither(parser.parse(framed).leftMap(err => UnexpectedJsonLd(err.getMessage())))
      ctxValue   <- context(frame, f, options)
    } yield (framedJson, ctxValue)

  // $COVERAGE-OFF$
  // TODO: create the DocumentLoader and pass it to Jena: https://issues.apache.org/jira/browse/JENA-1959
  override def toRdf(
      input: Json
  )(implicit opts: JsonLdOptions, resolution: RemoteContextResolution): IOErrorOr[Model] = {
    val model       = ModelFactory.createDefaultModel()
    val initBuilder = RDFParser.create.fromString(input.noSpaces).lang(Lang.JSONLD)
    val builder     = opts.base.fold(initBuilder)(base => initBuilder.base(base.toString))
    catchTryErr(builder.parse(StreamRDFLib.graph(model.getGraph)), "toRdf").as(model)
  }

  override def fromRdf(input: Model)(implicit options: JsonLdOptions): IOErrorOr[Json] =
    for {
      expanded     <- catchTryErr(RDFWriter.create.lang(Lang.JSONLD).source(input).asString(), "fromRdf")
      expandedJson <- IO.fromEither(parser.parse(expanded)).leftMap(err => UnexpectedJsonLd(err.getMessage()))
    } yield expandedJson
  // $COVERAGE-ON$

  override def context[Ctx <: JsonLdContext](value: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IOErrorOr[Ctx] =
    f match {
      case ContextFields.Skip    => context(value, f, toOpts())
      case ContextFields.Include => remoteContextLoader(value).flatMap(dl => context(value, f, toOpts(dl)))
    }

  private def context[Ctx <: JsonLdContext](
      value: Json,
      f: ContextFields[Ctx],
      options: JsonLdJavaOptions
  ): IOErrorOr[Ctx] = {
    val cxtValue = value.topContextValueOrEmpty
    f match {

      case ContextFields.Skip => IO.fromEither(Right(RawJsonLdContext(cxtValue)))

      case ContextFields.Include =>
        IO.fromTry(Try(new Context(options).parse(JsonUtils.fromString(cxtValue.noSpaces))))
          .leftMap(err => UnexpectedJsonLdContext(err.getMessage))
          .map { ctx =>
            val pm      = ctx.getPrefixes(true).asScala.toMap.map { case (k, v) => k -> iri"$v" }
            val aliases = (ctx.getPrefixes(false).asScala.toMap -- pm.keySet).map { case (k, v) => k -> iri"$v" }

            ExtendedJsonLdContext(cxtValue, getIri(ctx, keywords.base), getIri(ctx, keywords.vocab), aliases, pm)
          }
    }
  }

  private def remoteContextLoader(
      jsons: Json*
  )(implicit resolution: RemoteContextResolution): IOErrorOr[DocumentLoader] =
    IO.parTraverseUnordered(jsons)(resolution(_))
      .leftMap(RemoteContextError)
      .map {
        _.foldLeft(Map.empty[Uri, Json])(_ ++ _).foldLeft(new DocumentLoader()) {
          case (dl, (uri, cxt)) => dl.addInjectedDoc(uri.toString(), Json.obj(keywords.context -> cxt).noSpaces)
        }
      }

  private def toOpts(dl: DocumentLoader = new DocumentLoader)(implicit options: JsonLdOptions): JsonLdJavaOptions = {
    val opts = new JsonLdJavaOptions()
    options.base.foreach(b => opts.setBase(b.toString))
    opts.setCompactArrays(options.compactArrays)
    opts.setCompactArrays(options.compactArrays)
    opts.setProcessingMode(options.processingMode)
    opts.setProduceGeneralizedRdf(options.produceGeneralizedRdf)
    opts.setPruneBlankNodeIdentifiers(options.pruneBlankNodeIdentifiers)
    opts.setUseNativeTypes(options.useNativeTypes)
    opts.setUseRdfType(options.useRdfType)
    opts.setEmbed(options.embed)
    opts.setExplicit(options.explicit)
    opts.setOmitGraph(options.omitGraph)
    opts.setOmitDefault(options.omitDefault)
    opts.setRequireAll(options.requiredAll)
    opts.setDocumentLoader(dl)
    opts
  }
  private def catchTryErr[A](value: => A, stage: String): IOErrorOr[A]       =
    IO.fromTry(Try(value)).leftMap(err => JsonLdApiError(err.getMessage, stage))

  private def getIri(ctx: Context, key: String): Option[IRI] =
    Option(ctx.get(key)).collectFirstSome { case str: String => str.toIri.toOption }

}
