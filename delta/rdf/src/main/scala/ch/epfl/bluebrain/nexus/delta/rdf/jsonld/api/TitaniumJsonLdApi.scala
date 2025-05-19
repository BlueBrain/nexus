package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{ConversionError, RemoteContextError, UnexpectedJsonLd, UnexpectedJsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApiConfig.ErrorHandling
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.TitaniumJsonLdApi.tryExpensiveIO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.*
import ch.epfl.bluebrain.nexus.delta.rdf.{ExplainResult, RdfError}
import com.apicatalog.jsonld.JsonLdOptions.RdfDirection
import com.apicatalog.jsonld.context.ActiveContext
import com.apicatalog.jsonld.document.{JsonDocument, RdfDocument}
import com.apicatalog.jsonld.loader.DocumentLoader
import com.apicatalog.jsonld.processor.{ProcessingRuntime, ToRdfProcessor}
import com.apicatalog.jsonld.uri.UriValidationPolicy
import com.apicatalog.jsonld.{JsonLd, JsonLdError, JsonLdErrorCode, JsonLdOptions as TitaniumJsonLdOptions}
import com.apicatalog.rdf.RdfDatasetSupplier
import io.circe.jakartajson.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import jakarta.json.{JsonArray, JsonStructure}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.jena.irix.IRIxResolver
import org.apache.jena.riot.RIOT
import org.apache.jena.riot.system.*
import org.apache.jena.sparql.core.DatasetGraph

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
  * Json-LD high level API implementation by Json-LD Java library
  */
final class TitaniumJsonLdApi(config: JsonLdApiConfig) extends JsonLdApi {

  private def circeToDocument(json: Json) =
    circeToJakarta(json) match {
      case structure: JsonStructure => JsonDocument.of(structure)
      case _                        =>
        throw new JsonLdError(
          JsonLdErrorCode.LOADING_DOCUMENT_FAILED,
          "A json object or a json array were expected to build a document"
        )
    }

  override private[rdf] def compact(
      input: Json,
      ctx: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonObject] = {
    for {
      document  <- tryExpensiveIO(circeToDocument(input), "building input")
      context   <- tryExpensiveIO(ctx.titaniumDocument, "building context")
      options   <- documentLoader(input, ctx.contextObj.asJson).map(toOpts)
      compacted <-
        tryExpensiveIO(jakartaJsonToCirceObject(JsonLd.compact(document, context).options(options).get()), "compacting")
    } yield compacted
  }

  override private[rdf] def expand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[Seq[JsonObject]] =
    explainExpand(input).map(_.value)

  override private[rdf] def explainExpand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[ExplainResult[Seq[JsonObject]]] =
    for {
      document       <- tryExpensiveIO(circeToDocument(input), "building input")
      remoteContexts <- remoteContexts(input)
      options         = toOpts(TitaniumDocumentLoader(remoteContexts))
      expanded       <- tryExpensiveIO(jakartaJsonToCirce(JsonLd.expand(document).options(options).get()), "expanding")
      expandedSeqObj <- IO.fromEither(toSeqJsonObjectOrErr(expanded))
    } yield ExplainResult(remoteContexts, expandedSeqObj)

  override private[rdf] def frame(
      input: Json,
      frame: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonObject] =
    for {
      obj     <- tryExpensiveIO(circeToDocument(input), "building input")
      ff      <- tryExpensiveIO(circeToDocument(frame), "building frame")
      options <- documentLoader(input, frame).map(toOpts)
      framed  <- tryExpensiveIO(jakartaJsonToCirceObject(JsonLd.frame(obj, ff).options(options).get), "framing")
    } yield framed

  override private[rdf] def toRdf(input: Json)(implicit opts: JsonLdOptions): IO[DatasetGraph] = {
    def toRdf: DatasetGraph = {
      val consumer     = new RdfDatasetSupplier
      ToRdfProcessor.toRdf(
        consumer,
        circeToJakarta(input).asInstanceOf[JsonArray],
        toOpts(TitaniumDocumentLoader.empty)
      )
      val rdfDataset   = consumer.get()
      val errorHandler = config.errorHandling match {
        case ErrorHandling.Default   => ErrorHandlerFactory.getDefaultErrorHandler
        case ErrorHandling.Strict    => ErrorHandlerFactory.errorHandlerStrictNoLogging
        case ErrorHandling.NoWarning => ErrorHandlerFactory.errorHandlerNoWarnings
      }

      val iriResolver = IRIxResolver.create
        .base(opts.base.map(_.toString).orNull)
        .resolve(!config.strict)
        .allowRelative(!config.strict)
        .build()
      val profile     = new CDTAwareParserProfile(
        RiotLib.factoryRDF,
        errorHandler,
        iriResolver,
        PrefixMapFactory.create,
        RIOT.getContext.copy,
        config.extraChecks,
        config.strict
      )
      JenaTitanium.convert(rdfDataset, profile)
    }

    tryExpensiveIO(toRdf, "toRdf")
  }

  override private[rdf] def fromRdf(
      input: DatasetGraph
  )(implicit opts: JsonLdOptions): IO[Seq[JsonObject]] = {
    def fromRdf = {
      val rdfDataset = JenaTitanium.convert(input)
      val jsonArray  = jakartaJsonToCirce(
        JsonLd.fromRdf(RdfDocument.of(rdfDataset)).options(toOpts(TitaniumDocumentLoader.empty)).get()
      )
      toSeqJsonObjectOrErr(jsonArray)
    }

    tryExpensiveIO(fromRdf, "fromRdf").rethrow
  }

  override private[rdf] def context(
      value: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[JsonLdContext] =
    for {
      dl                       <- documentLoader(value.contextObj.asJson)
      opts                      = toOpts(dl)
      contextValue              = circeToJakarta(value.value)
      ctx                      <- IO.fromTry(Try(new ActiveContext(ProcessingRuntime.of(opts)).newContext.create(contextValue, null)))
                                    .adaptError { err => UnexpectedJsonLdContext(err.getMessage) }
      base                      = Option(ctx.getBaseUri).map { base => iri"$base" }
      vocab                     = Option(ctx.getVocabularyMapping).map { vocab => iri"$vocab" }
      (aliases, prefixMappings) = extractTerms(ctx)
    } yield JsonLdContext(value, base, vocab, aliases, prefixMappings)

  private def extractTerms(activeContext: ActiveContext) = {
    val init = Map.empty[String, Iri]
    activeContext.getTermsMapping.asScala.foldLeft((init, init)) { case ((aliases, prefixMappings), (key, term)) =>
      val entry = key -> iri"${term.getUriMapping}"
      if (term.isPrefix)
        (aliases, prefixMappings + entry)
      else
        (aliases + entry, prefixMappings)
    }
  }

  private def remoteContexts(
      jsons: Json*
  )(implicit rcr: RemoteContextResolution): IO[Map[Iri, RemoteContext]] =
    jsons
      .parTraverse(rcr(_))
      .adaptError { case r: RemoteContextResolutionError => RemoteContextError(r) }
      .map(_.foldLeft(Map.empty[Iri, RemoteContext])(_ ++ _))

  private def documentLoader(jsons: Json*)(implicit rcr: RemoteContextResolution): IO[DocumentLoader] =
    remoteContexts(jsons*).map(TitaniumDocumentLoader(_))

  private def toOpts(dl: DocumentLoader)(implicit options: JsonLdOptions): TitaniumJsonLdOptions = {
    val opts = new TitaniumJsonLdOptions(dl)
    options.base.foreach(b => opts.setBase(new URI(b.toString)))
    opts.setCompactArrays(options.compactArrays)
    opts.setCompactToRelative(options.compactToRelative)
    opts.setOrdered(options.ordered)
    opts.setProcessingMode(options.processingMode)
    opts.setProduceGeneralizedRdf(options.produceGeneralizedRdf)
    options.rdfDirection.foreach { dir => opts.setRdfDirection(RdfDirection.valueOf(dir)) }
    opts.setUseNativeTypes(options.useNativeTypes)
    opts.setUseRdfType(options.useRdfType)
    opts.setEmbed(options.embed)
    opts.setExplicit(options.explicit)
    opts.setOmitDefault(options.omitDefault)
    opts.setOmitGraph(options.omitGraph)
    // Disabling uri validation, Jena handles it better at a later stage
    opts.setUriValidation(UriValidationPolicy.None)
    opts
  }

  private def toSeqJsonObjectOrErr(json: Json): Either[RdfError, Seq[JsonObject]] =
    json.asArray
      .flatMap(_.foldM(Vector.empty[JsonObject])((seq, json) => json.asObject.map(seq :+ _)))
      .toRight(UnexpectedJsonLd("Expected a sequence of Json Object"))
}

object TitaniumJsonLdApi {

  /**
    * Creates an API with a config with strict values
    */
  def strict: JsonLdApi =
    new TitaniumJsonLdApi(
      JsonLdApiConfig(strict = true, extraChecks = true, errorHandling = ErrorHandling.Strict)
    )

  /**
    * Creates an API with a config with lenient values
    */
  def lenient: JsonLdApi =
    new TitaniumJsonLdApi(
      JsonLdApiConfig(strict = false, extraChecks = false, errorHandling = ErrorHandling.NoWarning)
    )

  private[rdf] def tryExpensiveIO[A](value: => A, stage: String): IO[A] =
    IO.cede *> IO.fromEither(tryOrRdfError(value, stage)).guarantee(IO.cede)

  private[rdf] def tryOrRdfError[A](value: => A, stage: String): Either[RdfError, A] =
    Try(value).toEither.leftMap {
      case err: JsonLdError =>
        val rootMessage = ExceptionUtils.getRootCauseMessage(err)
        ConversionError(rootMessage, stage)
      case err              =>
        ConversionError(err.getMessage, stage)
    }
}
