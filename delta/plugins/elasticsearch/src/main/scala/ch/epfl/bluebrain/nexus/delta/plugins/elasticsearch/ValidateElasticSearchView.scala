package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{InvalidElasticSearchIndexPayload, InvalidPipeline, InvalidViewReferences, PermissionIsNotDefined, TooManyViewReferences, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewRejection, ElasticSearchViewValue, defaultElasticsearchMapping, defaultElasticsearchSettings}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ValidateAggregate}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ProjectionErr}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

import java.util.UUID

/**
  * Validate an [[ElasticSearchViewValue]] during command evaluation
  */
trait ValidateElasticSearchView {

  def apply(uuid: UUID, indexingRev: IndexingRev, v: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit]
}

object ValidateElasticSearchView {

  val always: ValidateElasticSearchView = (_: UUID, _: IndexingRev, _: ElasticSearchViewValue) => IO.unit

  def apply(
      validatePipeChain: PipeChain => Either[ProjectionErr, Unit],
      permissions: Permissions,
      client: ElasticSearchClient,
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors
  ): ValidateElasticSearchView =
    apply(
      validatePipeChain,
      permissions.fetchPermissionSet,
      client.createIndex(_, _, _).void,
      prefix,
      maxViewRefs,
      xas
    )

  def apply(
      validatePipeChain: PipeChain => Either[ProjectionErr, Unit],
      fetchPermissionSet: UIO[Set[Permission]],
      createIndex: (IndexLabel, Option[JsonObject], Option[JsonObject]) => HttpResult[Unit],
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors
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
        defaultMapping  <- defaultElasticsearchMapping
        defaultSettings <- defaultElasticsearchSettings
        _               <- fetchPermissionSet.flatMap { set =>
                             IO.raiseUnless(set.contains(value.permission))(PermissionIsNotDefined(value.permission))
                           }
        _               <- IO.fromEither(value.pipeChain.traverse(validatePipeChain)).mapError(InvalidPipeline)
        _               <- createIndex(
                             IndexLabel.fromView(prefix, uuid, indexingRev),
                             value.mapping.orElse(Some(defaultMapping)),
                             value.settings.orElse(Some(defaultSettings))
                           )
                             .mapError {
                               case err: HttpClientStatusError => InvalidElasticSearchIndexPayload(err.jsonBody)
                               case err                        => WrappedElasticSearchClientError(err)
                             }
      } yield ()

    override def apply(uuid: UUID, indexingRev: IndexingRev, value: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit] =
      value match {
        case v: AggregateElasticSearchViewValue =>
          validateAggregate(v.views)
        case v: IndexingElasticSearchViewValue  =>
          validateIndexing(uuid, indexingRev, v)
      }

  }

}
