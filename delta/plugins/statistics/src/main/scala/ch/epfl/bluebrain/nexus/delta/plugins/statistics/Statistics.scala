package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptySeq
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.{ioContentOf, ioJsonObjectContentOf}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsRejection.{InvalidPropertyType, WrappedElasticSearchRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.{PropertiesStatistics, StatisticsGraph, StatisticsRejection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, JsonObject}
import monix.bio.{IO, Task}

trait Statistics {

  /**
    * The relationship statistics between different types for the passed ''projectRef''
    */
  def relationships(projectRef: ProjectRef): IO[StatisticsRejection, StatisticsGraph]

  /**
    * The properties statistics of the passed ''project'' and type ''tpe''.
    */
  def properties(projectRef: ProjectRef, tpe: IdSegment): IO[StatisticsRejection, PropertiesStatistics]

}

object Statistics {

  final def apply(
      client: ElasticSearchClient,
      projects: Projects
  )(implicit config: ExternalIndexingConfig): Task[Statistics] =
    for {
      script <- scriptContent
      _      <- client.createScript(updateRelationshipsScriptId, script)
    } yield new Statistics {

      private val expandIri: ExpandIri[InvalidPropertyType] = new ExpandIri(InvalidPropertyType.apply)

      private val propertiesAggQuery =
        ioJsonObjectContentOf("elasticsearch/paths-properties-aggregations.json")
          .logAndDiscardErrors("ElasticSearch 'paths-properties-aggregations.json' template not found")
          .memoizeOnSuccess

      private val relationshipsAggQuery =
        ioJsonObjectContentOf("elasticsearch/paths-relationships-aggregations.json")
          .logAndDiscardErrors("ElasticSearch 'paths-relationships-aggregations.json' template not found")
          .memoizeOnSuccess

      private def propertiesAggQueryFor(tpe: Iri)                                                  =
        propertiesAggQuery.map(_.replace("@type" -> "{{type}}", tpe))

      override def relationships(projectRef: ProjectRef): IO[StatisticsRejection, StatisticsGraph] =
        for {
          _     <- projects.fetchProject[StatisticsRejection](projectRef)
          query <- relationshipsAggQuery
          stats <- client
                     .searchAs[StatisticsGraph](QueryBuilder(query), idx(projectRef).value, Query.Empty)
                     .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
        } yield stats

      override def properties(projectRef: ProjectRef, tpe: IdSegment): IO[StatisticsRejection, PropertiesStatistics] = {

        def search(tpe: Iri, idx: IndexLabel, query: JsonObject) = {
          implicit val d: Decoder[PropertiesStatistics] = propertiesDecoderFromEsAggregations(tpe)
          client
            .searchAs[PropertiesStatistics](QueryBuilder(query).withTotalHits(true), idx.value, Query.Empty)
            .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
        }

        for {
          project <- projects.fetchProject[StatisticsRejection](projectRef)
          tpeIri  <- expandIri(tpe, project)
          query   <- propertiesAggQueryFor(tpeIri)
          stats   <- search(tpeIri, idx(projectRef), query)
        } yield stats

      }
    }

  implicit private val classLoader: ClassLoader = getClass.getClassLoader
  implicit private val logger: Logger           = Logger[Statistics]

  private val scriptContent =
    ioContentOf("elasticsearch/update_relationships_script.painless")
      .logAndDiscardErrors("ElasticSearch script 'update_relationships_script.painless' template not found")
      .memoizeOnSuccess

  /**
    * The id for the type statistics elasticsearch view
    */
  final val typeStats = nxv + "typeStatisticsIndex"

  /**
    * The default Statistics API mappings
    */
  final val mappings: ApiMappings = ApiMappings("typeStats" -> typeStats)

  final val updateRelationshipsScriptId = "updateRelationships"

  private[statistics] def toPaths(key: String): Either[String, NonEmptySeq[Iri]] =
    key.split(" / ").toVector.foldM(Vector.empty[Iri])((acc, k) => Iri.absolute(k).map(acc :+ _)).flatMap {
      case Seq(first, tail @ _*) => Right(NonEmptySeq(first, tail))
      case _                     => Left("Empty Path")
    }

  private[statistics] def idx(projectRef: ProjectRef)(implicit config: ExternalIndexingConfig): IndexLabel =
    if (config.prefix.isEmpty)
      IndexLabel.unsafe(s"${UrlUtils.encode(projectRef.toString)}_statistics")
    else
      IndexLabel.unsafe(s"${config.prefix}_${UrlUtils.encode(projectRef.toString)}_statistics")

  private[statistics] def projectionId(projectRef: ProjectRef): ViewProjectionId =
    ViewProjectionId(s"statistics-$projectRef")

  private[statistics] def name(iri: Iri): String =
    (iri.fragment orElse iri.lastSegment) getOrElse iri.toString
}
