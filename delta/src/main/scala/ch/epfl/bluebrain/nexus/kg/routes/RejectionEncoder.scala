package ch.epfl.bluebrain.nexus.kg.routes

import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidJsonLD
import ch.epfl.bluebrain.nexus.kg.resources.{Rejection, Resource, ResourceV}
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.Compacted
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import io.circe.Json

/**
  * An encoder that can reject
  *
  * @tparam A the generic type
  */
sealed trait RejectionEncoder[A] {

  /**
    * An evaluation of the provided value produces either a Rejection or a Json
    * @param value the provided value to evaluate
    */
  def apply(value: A): Either[Rejection, Json]
}

object RejectionEncoder {

  implicit final def rejectionEncoder(implicit
      outputFormat: JsonLDOutputFormat = Compacted
  ): RejectionEncoder[ResourceV] =
    new RejectionEncoder[ResourceV] {
      override def apply(value: ResourceV): Either[Rejection, Json] =
        ResourceEncoder.json(value).leftMap(err => InvalidJsonLD(err))
    }

  implicit final def rejectionEncoder(implicit
      config: AppConfig,
      project: ProjectResource
  ): RejectionEncoder[Resource] =
    new RejectionEncoder[Resource] {
      override def apply(value: Resource): Either[Rejection, Json] =
        ResourceEncoder.json(value).leftMap(err => InvalidJsonLD(err))
    }
}
