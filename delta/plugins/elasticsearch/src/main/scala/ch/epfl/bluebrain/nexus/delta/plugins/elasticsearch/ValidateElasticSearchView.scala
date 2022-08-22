package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{InvalidElasticSearchIndexPayload, InvalidPipeline, InvalidViewReferences, PermissionIsNotDefined, TooManyViewReferences, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultElasticsearchMapping, defaultElasticsearchSettings, ElasticSearchViewRejection, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{Pipe, PipeConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityCheck, EntityDependencyStore}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

import java.util.UUID

/**
  * Validate an [[ElasticSearchViewValue]] during command evaluation
  */
trait ValidateElasticSearchView {

  def apply(uuid: UUID, rev: Int, v: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit]
}

object ValidateElasticSearchView {

  def apply(
      pipeConfig: PipeConfig,
      permissions: Permissions,
      client: ElasticSearchClient,
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors
  ): ValidateElasticSearchView =
    apply(
      pipeConfig,
      permissions.fetchPermissionSet,
      client.createIndex(_, _, _).void,
      prefix,
      maxViewRefs,
      xas
    )

  def apply(
      pipeConfig: PipeConfig,
      fetchPermissionSet: UIO[Set[Permission]],
      createIndex: (IndexLabel, Option[JsonObject], Option[JsonObject]) => HttpResult[Unit],
      prefix: String,
      maxViewRefs: Int,
      xas: Transactors
  ): ValidateElasticSearchView = new ValidateElasticSearchView {

    private def validateAggregate(value: AggregateElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit] =
      EntityCheck.raiseMissingOrDeprecated[Iri, InvalidViewReferences](
        entityType,
        value.views.value.map { v => v.project -> v.viewId },
        missing => InvalidViewReferences(missing.map { case (p, id) => ViewRef(p, id) }),
        xas
      ) >> value.views.value.toList
        .foldLeftM(value.views.value.size) { (acc, ref) =>
          EntityDependencyStore.recursiveList(ref.project, ref.viewId, xas).map { r =>
            acc + r.size
          }
        }
        .flatMap { totalRefs =>
          IO.raiseWhen(totalRefs > maxViewRefs)(TooManyViewReferences(totalRefs, maxViewRefs))
        }

    private def validateIndexing(uuid: UUID, rev: Int, value: IndexingElasticSearchViewValue) =
      for {
        defaultMapping  <- defaultElasticsearchMapping
        defaultSettings <- defaultElasticsearchSettings
        _               <- fetchPermissionSet.flatMap { set =>
                             IO.raiseUnless(set.contains(value.permission))(PermissionIsNotDefined(value.permission))
                           }
        _               <- IO.fromEither(Pipe.validate(value.pipeline, pipeConfig)).mapError(InvalidPipeline)
        _               <- createIndex(
                             IndexLabel.fromView(prefix, uuid, rev),
                             value.mapping.orElse(Some(defaultMapping)),
                             value.settings.orElse(Some(defaultSettings))
                           )
                             .mapError {
                               case err: HttpClientStatusError => InvalidElasticSearchIndexPayload(err.jsonBody)
                               case err                        => WrappedElasticSearchClientError(err)
                             }
      } yield ()

    override def apply(uuid: UUID, rev: Int, value: ElasticSearchViewValue): IO[ElasticSearchViewRejection, Unit] =
      value match {
        case v: AggregateElasticSearchViewValue =>
          validateAggregate(v)
        case v: IndexingElasticSearchViewValue  =>
          validateIndexing(uuid, rev, v)
      }

  }

}
