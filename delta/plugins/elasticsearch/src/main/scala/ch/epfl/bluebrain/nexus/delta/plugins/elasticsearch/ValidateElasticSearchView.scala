package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{InvalidElasticSearchIndexPayload, InvalidPipeline, InvalidViewReferences, PermissionIsNotDefined, TooManyViewReferences, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ValidateAggregate}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ProjectionErr}
import io.circe.JsonObject

import java.util.UUID

/**
  * Validate an [[ElasticSearchViewValue]] during command evaluation
  */
trait ValidateElasticSearchView {

  def apply(uuid: UUID, indexingRev: IndexingRev, v: ElasticSearchViewValue): IO[Unit]
}

object ValidateElasticSearchView {

  val always: ValidateElasticSearchView = (_: UUID, _: IndexingRev, _: ElasticSearchViewValue) => IO.unit

  def apply(
      validatePipeChain: PipeChain => Either[ProjectionErr, Unit],
      permissions: Permissions,
      client: ElasticSearchClient,
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors,
      defaultMapping: JsonObject,
      defaultSettings: JsonObject
  ): ValidateElasticSearchView =
    apply(
      validatePipeChain,
      permissions.fetchPermissionSet,
      client.createIndex(_, _, _).void,
      prefix,
      maxViewRefs,
      xas,
      defaultMapping,
      defaultSettings
    )

  def apply(
      validatePipeChain: PipeChain => Either[ProjectionErr, Unit],
      fetchPermissionSet: IO[Set[Permission]],
      createIndex: (IndexLabel, Option[JsonObject], Option[JsonObject]) => HttpResult[Unit],
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors,
      defaultMapping: JsonObject,
      defaultSettings: JsonObject
  ): ValidateElasticSearchView = new ValidateElasticSearchView {

    private val validateAggregate = ValidateAggregate(
      ElasticSearchViews.entityType,
      InvalidViewReferences,
      maxViewRefs,
      TooManyViewReferences,
      xas
    )

    private def validateIndexing(uuid: UUID, indexingRev: IndexingRev, value: IndexingElasticSearchViewValue) =
      for {
        _ <- fetchPermissionSet.flatMap { set =>
               IO.raiseUnless(set.contains(value.permission))(PermissionIsNotDefined(value.permission))
             }
        _ <- IO.fromEither(value.pipeChain.traverse(validatePipeChain).leftMap(InvalidPipeline))
        _ <- createIndex(
               IndexLabel.fromView(prefix, uuid, indexingRev),
               value.mapping.orElse(Some(defaultMapping)),
               value.settings.orElse(Some(defaultSettings))
             ).toCatsIO
               .adaptError {
                 case err: HttpClientStatusError => InvalidElasticSearchIndexPayload(err.jsonBody)
                 case err: HttpClientError       => WrappedElasticSearchClientError(err)
               }
      } yield ()

    override def apply(
        uuid: UUID,
        indexingRev: IndexingRev,
        value: ElasticSearchViewValue
    ): IO[Unit] = value match {
      case v: AggregateElasticSearchViewValue => validateAggregate(v.views)
      case v: IndexingElasticSearchViewValue  => validateIndexing(uuid, indexingRev, v)
    }

  }

}
