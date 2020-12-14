package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{ConversionError, RemoteContextCircularDependency, RemoteContextError, UnexpectedJsonLd, UnexpectedJsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import com.github.jsonldjava.core.JsonLdError.Error.RECURSIVE_CONTEXT_INCLUSION
import com.github.jsonldjava.core.{Context, DocumentLoader, JsonLdError, JsonLdProcessor, JsonLdOptions => JsonLdJavaOptions}
import com.github.jsonldjava.utils.JsonUtils
import io.circe.{parser, Json, JsonObject}
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFFormat.{JSONLD_EXPAND_FLAT => EXPAND}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{JsonLDWriteContext, Lang, RDFParser, RDFWriter}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Json-LD high level API implementation by Json-LD Java library
  */
object JsonLdJavaApi extends JsonLdApi {

  System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

  override private[rdf] def compact(
      input: Json,
      ctx: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonObject] =
    for {
      obj          <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      ctxObj       <- ioTryOrRdfError(JsonUtils.fromString(ctx.toString), "building context")
      options      <- documentLoader(input, ctx.contextObj.asJson).map(toOpts)
      compacted    <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.compact(obj, ctxObj, options)), "compacting")
      compactedObj <- IO.fromEither(toJsonObjectOrErr(compacted))
    } yield compactedObj

  override private[rdf] def expand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, Seq[JsonObject]] =
    for {
      obj            <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      options        <- documentLoader(input).map(toOpts)
      expanded       <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.expand(obj, options)), "expanding")
      expandedSeqObj <- IO.fromEither(toSeqJsonObjectOrErr(expanded))
    } yield expandedSeqObj

  override private[rdf] def frame(
      input: Json,
      frame: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonObject] =
    for {
      obj       <- ioTryOrRdfError(JsonUtils.fromString(input.noSpaces), "building input")
      ff        <- ioTryOrRdfError(JsonUtils.fromString(frame.noSpaces), "building frame")
      options   <- documentLoader(input, frame).map(toOpts)
      framed    <- ioTryOrRdfError(JsonUtils.toString(JsonLdProcessor.frame(obj, ff, options)), "framing")
      framedObj <- IO.fromEither(toJsonObjectOrErr(framed))
    } yield framedObj

  // TODO: Right now this step has to be done from a JSON-LD expanded document,
  // since the DocumentLoader cannot be passed to Jena yet: https://issues.apache.org/jira/browse/JENA-1959
  override private[rdf] def toRdf(input: Json)(implicit opts: JsonLdOptions): Either[RdfError, Model] = {
    val model       = ModelFactory.createDefaultModel()
    val initBuilder = RDFParser.create.fromString(input.noSpaces).lang(Lang.JSONLD)
    val builder     = opts.base.fold(initBuilder)(base => initBuilder.base(base.toString))
    tryOrRdfError(builder.parse(StreamRDFLib.graph(model.getGraph)), "toRdf").as(model)
  }

  override private[rdf] def fromRdf(input: Model)(implicit opts: JsonLdOptions): Either[RdfError, Seq[JsonObject]] = {
    val c = new JsonLDWriteContext()
    c.setOptions(toOpts())
    for {
      expanded       <- tryOrRdfError(RDFWriter.create.format(EXPAND).source(input).context(c).asString(), "fromRdf")
      expandedSeqObj <- toSeqJsonObjectOrErr(expanded)
    } yield expandedSeqObj
  }

  override private[rdf] def context(
      value: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonLdContext] =
    for {
      dl     <- documentLoader(value.contextObj.asJson)
      jOpts   = toOpts(dl)
      ctx    <- IO.fromTry(Try(new Context(jOpts).parse(JsonUtils.fromString(value.toString))))
                  .leftMap(err => UnexpectedJsonLdContext(err.getMessage))
      pm      = ctx.getPrefixes(true).asScala.toMap.map { case (k, v) => k -> iri"$v" }
      aliases = (ctx.getPrefixes(false).asScala.toMap -- pm.keySet).map { case (k, v) => k -> iri"$v" }
    } yield JsonLdContext(value, getIri(ctx, keywords.base), getIri(ctx, keywords.vocab), aliases, pm)

  private def documentLoader(jsons: Json*)(implicit rcr: RemoteContextResolution): IO[RdfError, DocumentLoader] =
    IO.parTraverseUnordered(jsons)(rcr(_))
      .leftMap(RemoteContextError)
      .map {
        _.foldLeft(Map.empty[Iri, ContextValue])(_ ++ _).foldLeft(new DocumentLoader()) { case (dl, (iri, ctx)) =>
          dl.addInjectedDoc(iri.toString, ctx.contextObj.asJson.noSpaces)
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

  private def getIri(ctx: Context, key: String): Option[Iri]                  =
    Option(ctx.get(key)).collectFirstSome { case str: String => str.toIri.toOption }

  private def toJsonObjectOrErr(string: String): Either[RdfError, JsonObject] =
    for {
      json <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      obj  <- json.asObject.toRight(UnexpectedJsonLd("Expected a Json Object"))
    } yield obj

  private def toSeqJsonObjectOrErr(string: String): Either[RdfError, Seq[JsonObject]] =
    for {
      json   <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      objSeq <- json.asArray
                  .flatMap(_.foldM(Vector.empty[JsonObject])((seq, json) => json.asObject.map(seq :+ _)))
                  .toRight(UnexpectedJsonLd("Expected a sequence of Json Object"))
    } yield objSeq

  private[rdf] def ioTryOrRdfError[A](value: => A, stage: String): IO[RdfError, A] =
    IO.fromEither(tryOrRdfError(value, stage))

  private[rdf] def tryOrRdfError[A](value: => A, stage: String): Either[RdfError, A] =
    Try(value).toEither.leftMap {
      case err: JsonLdError if err.getType == RECURSIVE_CONTEXT_INCLUSION =>
        val iri = Iri(err.getMessage.replace(s"$RECURSIVE_CONTEXT_INCLUSION:", "").trim).getOrElse(iri"")
        RemoteContextCircularDependency(iri)
      case err                                                            =>
        ConversionError(err.getMessage, stage)
    }
}
