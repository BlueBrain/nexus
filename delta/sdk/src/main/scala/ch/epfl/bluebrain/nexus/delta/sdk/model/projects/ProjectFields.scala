package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

/**
  * Type that represents a project payload for creation and update requests.
  *
  * @param description an optional description
  * @param apiMappings the API mappings
  * @param base        an optional base Iri for generated resource IDs ending with ''/'' or ''#''
  * @param vocab       an optional vocabulary for resources with no context ending with ''/'' or ''#''
  */
final case class ProjectFields(
    description: Option[String],
    apiMappings: ApiMappings = ApiMappings.empty,
    base: Option[PrefixIri],
    vocab: Option[PrefixIri]
) {

  /**
    * @return the current base or a generated one based on the ''baseUri'' and the project ref
    */
  def baseOrGenerated(projectRef: ProjectRef)(implicit baseUri: BaseUri): PrefixIri =
    base.getOrElse(
      PrefixIri.unsafe(
        (baseUri.endpoint / "resources" / projectRef.organization.value / projectRef.project.value / "_")
          .finalSlash()
          .toIri
      )
    )

  /**
    * @return the current vocab or a generated one based on the ''baseUri'' and the project ref
    */
  def vocabOrGenerated(projectRef: ProjectRef)(implicit baseUri: BaseUri): PrefixIri =
    vocab.getOrElse(
      PrefixIri.unsafe(
        (baseUri.endpoint / "vocabs" / projectRef.organization.value / projectRef.project.value).finalSlash().toIri
      )
    )

}

object ProjectFields {

  @nowarn("cat=unused")
  implicit final private val configuration: Configuration   = Configuration.default.withStrictDecoding.withDefaults
  implicit val projectFieldsDecoder: Decoder[ProjectFields] = deriveConfiguredDecoder[ProjectFields]

}
