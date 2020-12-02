package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import scala.annotation.nowarn
import scala.collection.immutable.VectorMap

/**
  * Report describing how the resource resolution went for each resolver
  * @param history how the resolution went
  */
final case class ResourceResolutionReport(history: Vector[ResolverReport])

object ResourceResolutionReport {

  /**
    * Subreport describing how the resolution went for a single resolver
    */
  sealed trait ResolverReport extends Product with Serializable {

    /**
      * @return the resolver
      */
    def resolverId: Iri

    /**
      * @return Causes of the failed attempts to resolve a resource with this resolver
      */
    def rejections: VectorMap[ProjectRef, ResolverResolutionRejection]

    def success: Boolean
  }

  /**
    * Report failures for a single resolver
    */
  final case class ResolverFailedReport(resolverId: Iri, rejections: VectorMap[ProjectRef, ResolverResolutionRejection])
      extends ResolverReport {
    override def success: Boolean = false
  }

  /**
    * Report success for a single resolver with previous attempts in case of a cross-project resolver
    */
  final case class ResolverSuccessReport(
      resolverId: Iri,
      rejections: VectorMap[ProjectRef, ResolverResolutionRejection]
  ) extends ResolverReport {
    override def success: Boolean = true
  }

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default

  implicit val resolverReportEncoder: Encoder.AsObject[ResolverReport] = {
    Encoder.AsObject.instance { r =>
      JsonObject(
        "resolverId" -> r.resolverId.asJson,
        "success"    -> r.success.asJson,
        "rejections" -> Json.fromValues(
          r.rejections.map { case (project, rejection) =>
            Json.obj("project" -> project.asJson, "cause" -> rejection.asJson)
          }
        )
      )
    }
  }

  implicit val resourceResolutionReportEncoder: Encoder.AsObject[ResourceResolutionReport] =
    deriveConfiguredEncoder[ResourceResolutionReport]

}
