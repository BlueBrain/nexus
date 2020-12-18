package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceType
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, SchemaResource}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}
import monix.bio.IO

import scala.collection.immutable.VectorMap

/**
  * Result of a [[MultiResolution]]
  */
sealed abstract class MultiResolutionResult[R] {
  def reports: VectorMap[ResourceType, R]
}

object MultiResolutionResult {

  /**
    * Creates a [[ResourceResolved]]
    * @param value    the resolved resource
    * @param reports  the resolution reports for the different resource types
    */
  def resource[R](
      value: DataResource,
      firstReport: (ResourceType, R),
      reports: (ResourceType, R)*
  ): ResourceResolved[R] =
    ResourceResolved(VectorMap(firstReport) ++ reports, value)

  /**
    * Creates a [[SchemaResolved]]
    * @param value    the resolved schema
    * @param reports  the resolution reports for the different resource types
    */
  def schema[R](value: SchemaResource, firstReport: (ResourceType, R), reports: (ResourceType, R)*): SchemaResolved[R] =
    SchemaResolved(VectorMap(firstReport) ++ reports, value)

  /**
    * Multi resolution ended resolving a [[DataResource]]
    * @param reports the different reports describing the steps of the resolution
    * @param value   the value resolved as a [[DataResource]]
    */
  final case class ResourceResolved[R](reports: VectorMap[ResourceType, R], value: DataResource)
      extends MultiResolutionResult[R]

  /**
    * Multi resolution ended resolving a [[SchemaResource]]
    * @param reports the different reports describing the steps of the resolution
    * @param value   the value resolved as a [[SchemaResource]]
    */
  final case class SchemaResolved[R](reports: VectorMap[ResourceType, R], value: SchemaResource)
      extends MultiResolutionResult[R]

  implicit def multiResolutionResultEncoder[R: Encoder.AsObject]: Encoder.AsObject[MultiResolutionResult[R]] =
    Encoder.AsObject.instance { r =>
      JsonObject(
        "reports" -> JsonObject
          .fromMap(
            r.reports.map { case (resourceType, report) =>
              resourceType.name.value.toLowerCase -> report.asJson
            }
          )
          .asJson
      )

    }

  private val context: ContextValue = ContextValue(contexts.resolvers)

  /**
    * When show report is set to true, only the report is serialized, otherwise only the value is returned
    * @param showReport the flag to serialize and return one part or the other
    */
  def multiResolutionJsonLdEncoder[R](showReport: Boolean)(implicit
      multiResolutionResultEncoder: Encoder[MultiResolutionResult[R]],
      dataResourceFEncoder: JsonLdEncoder[DataResource],
      schemaResourceFEncoder: JsonLdEncoder[SchemaResource]
  ): JsonLdEncoder[MultiResolutionResult[R]] = {
    if (showReport)
      JsonLdEncoder.computeFromCirce(context)
    else
      new JsonLdEncoder[MultiResolutionResult[R]] {
        override def context(result: MultiResolutionResult[R]): ContextValue =
          result match {
            case ResourceResolved(_, value) => dataResourceFEncoder.context(value)
            case SchemaResolved(_, value)   => schemaResourceFEncoder.context(value)
          }

        override def expand(
            result: MultiResolutionResult[R]
        )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
          result match {
            case ResourceResolved(_, value) => dataResourceFEncoder.expand(value)
            case SchemaResolved(_, value)   => schemaResourceFEncoder.expand(value)
          }

        override def compact(
            result: MultiResolutionResult[R]
        )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
          result match {
            case ResourceResolved(_, value) => dataResourceFEncoder.compact(value)
            case SchemaResolved(_, value)   => schemaResourceFEncoder.compact(value)
          }
      }
  }
}
