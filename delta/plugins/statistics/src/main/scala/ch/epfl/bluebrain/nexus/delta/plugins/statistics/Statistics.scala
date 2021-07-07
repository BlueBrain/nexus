package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import akka.http.scaladsl.model.Uri.Query
import cats.data.NonEmptySeq
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonObjectContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsRejection.{InvalidPropertyType, WrappedClasspathResourceError, WrappedElasticSearchRejection}
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
import io.circe.{Decoder, JsonObject}
import monix.bio.IO

trait Statistics {

  /**
    * The statistics for the relationships between different types for the passed ''projectRef''
    */
  def relationships(projectRef: ProjectRef): IO[StatisticsRejection, StatisticsGraph]

  /**
    * The statistics for the properties of the passed ''project'' and type ''tpe''.
    */
  def properties(projectRef: ProjectRef, tpe: IdSegment): IO[StatisticsRejection, PropertiesStatistics]

}

object Statistics {

  final def apply(
      client: ElasticSearchClient,
      projects: Projects
  )(implicit config: ExternalIndexingConfig): Statistics = new Statistics {

    private val expandIri: ExpandIri[InvalidPropertyType] = new ExpandIri(InvalidPropertyType.apply)

    implicit private val classLoader: ClassLoader = getClass.getClassLoader

    private val propertiesAggQuery =
      ioJsonObjectContentOf("elasticsearch/paths-properties-aggregations.json").memoize

    private val relationshipsAggQuery =
      ioJsonObjectContentOf("elasticsearch/paths-relationships-aggregations.json").memoize

    private def propertiesAggQueryFor(tpe: Iri)                                                  =
      propertiesAggQuery.map(_.replace("@type" -> "{{type}}", tpe))

    override def relationships(projectRef: ProjectRef): IO[StatisticsRejection, StatisticsGraph] =
      for {
        _     <- projects.fetchProject[StatisticsRejection](projectRef)
        query <- relationshipsAggQuery.mapError(WrappedClasspathResourceError)
        stats <- client
                   .searchAs[StatisticsGraph](QueryBuilder(query), Set(idx(projectRef).value), Query.Empty)
                   .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
      } yield stats

    override def properties(projectRef: ProjectRef, tpe: IdSegment): IO[StatisticsRejection, PropertiesStatistics] = {

      def search(tpe: Iri, idx: IndexLabel, query: JsonObject) = {
        implicit val d: Decoder[PropertiesStatistics] = propertiesDecoderFromEsAggregations(tpe)
        client
          .searchAs[PropertiesStatistics](QueryBuilder(query).withTotalHits(true), Set(idx.value), Query.Empty)
          .mapError(err => WrappedElasticSearchRejection(WrappedElasticSearchClientError(err)))
      }

      for {
        project <- projects.fetchProject[StatisticsRejection](projectRef)
        tpeIri  <- expandIri(tpe, project)
        query   <- propertiesAggQueryFor(tpeIri).mapError(WrappedClasspathResourceError)
        stats   <- search(tpeIri, idx(projectRef), query)
      } yield stats

    }
  }

  /**
    * The id for the type statistics elasticsearch view
    */
  final val typeStats = nxv + "typeStatisticsIndex"

  /**
    * The default Statistics API mappings
    */
  final val mappings: ApiMappings = ApiMappings("typeStats" -> typeStats)

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
