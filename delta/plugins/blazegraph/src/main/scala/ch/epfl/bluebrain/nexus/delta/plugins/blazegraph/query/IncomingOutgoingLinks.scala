package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.query

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlResultsJson
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQueryClient, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.InvalidResourceId
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.SparqlLink.{SparqlExternalLink, SparqlResourceLink}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, BlazegraphViewRejection, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.util.regex.Pattern.quote

trait IncomingOutgoingLinks {

  /**
    * List incoming links for a given resource.
    *
    * @param resourceId
    *   the resource identifier
    * @param project
    *   the project of the resource
    * @param pagination
    *   the pagination config
    */
  def incoming(resourceId: IdSegment, project: ProjectRef, pagination: FromPagination): IO[SearchResults[SparqlLink]]

  /**
    * List outgoing links for a given resource.
    *
    * @param resourceId
    *   the resource identifier
    * @param project
    *   the project of the resource
    * @param pagination
    *   the pagination config
    * @param includeExternalLinks
    *   whether to include links to resources not managed by Delta
    */
  def outgoing(
      resourceId: IdSegment,
      project: ProjectRef,
      pagination: FromPagination,
      includeExternalLinks: Boolean
  ): IO[SearchResults[SparqlLink]]

}

object IncomingOutgoingLinks {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent("incomingOutgoing")

  private val loader = ClasspathResourceLoader.withContext(getClass)

  final case class Queries(incoming: String, outgoingWithExternal: String, outgoingScoped: String)

  object Queries {
    def load: IO[Queries] = for {
      incoming             <- loader.contentOf("blazegraph/incoming.txt")
      outgoingWithExternal <- loader.contentOf("blazegraph/outgoing_include_external.txt")
      outgoingScoped       <- loader.contentOf("blazegraph/outgoing_scoped.txt")
    } yield Queries(incoming, outgoingWithExternal, outgoingScoped)
  }

  def apply(fetchContext: FetchContext, views: BlazegraphViews, client: SparqlQueryClient, queries: Queries)(implicit
      base: BaseUri
  ): IncomingOutgoingLinks = {
    def fetchNamespace: ProjectRef => IO[String] =
      views.fetchIndexingView(IriSegment(defaultViewId), _).map(_.namespace)
    apply(fetchContext, fetchNamespace, client, queries)
  }

  def apply(
      fetchContext: FetchContext,
      fetchDefaultNamespace: ProjectRef => IO[String],
      client: SparqlQueryClient,
      queries: Queries
  )(implicit base: BaseUri): IncomingOutgoingLinks = new IncomingOutgoingLinks {

    private val expandIri: ExpandIri[BlazegraphViewRejection] = new ExpandIri(InvalidResourceId.apply)

    override def incoming(
        resourceId: IdSegment,
        project: ProjectRef,
        pagination: FromPagination
    ): IO[SearchResults[SparqlLink]] = {
      for {
        iri       <- expandResourceIri(resourceId, project)
        namespace <- fetchDefaultNamespace(project)
        q          = SparqlQuery(replace(queries.incoming, iri, pagination))
        bindings  <- client.query(Set(namespace), q, SparqlResultsJson)
        links      = toSparqlLinks(bindings.value)
      } yield links
    }.span("incoming")

    override def outgoing(
        resourceId: IdSegment,
        project: ProjectRef,
        pagination: FromPagination,
        includeExternalLinks: Boolean
    ): IO[SearchResults[SparqlLink]] = {
      for {
        iri          <- expandResourceIri(resourceId, project)
        namespace    <- fetchDefaultNamespace(project)
        queryTemplate = if (includeExternalLinks) queries.outgoingWithExternal else queries.outgoingScoped
        q             = SparqlQuery(replace(queryTemplate, iri, pagination))
        bindings     <- client.query(Set(namespace), q, SparqlResultsJson)
        links         = toSparqlLinks(bindings.value)
      } yield links
    }.span("outgoing", Map("includeExternalLinks" -> includeExternalLinks))

    private def expandResourceIri(resourceId: IdSegment, project: ProjectRef) =
      fetchContext.onRead(project).flatMap { pc => expandIri(resourceId, pc) }

    private def replace(query: String, id: Iri, pagination: FromPagination): String =
      query
        .replaceAll(quote("{id}"), id.toString)
        .replaceAll(quote("{offset}"), pagination.from.toString)
        .replaceAll(quote("{size}"), pagination.size.toString)

    private def toSparqlLinks(sparqlResults: SparqlResults): SearchResults[SparqlLink] = {
      val (count, results) =
        sparqlResults.results.bindings
          .foldLeft((0L, List.empty[SparqlLink])) { case ((total, acc), bindings) =>
            val newTotal = bindings.get("total").flatMap(v => v.value.toLongOption).getOrElse(total)
            val res      = (SparqlResourceLink(bindings) orElse SparqlExternalLink(bindings))
              .map(_ :: acc)
              .getOrElse(acc)
            (newTotal, res)
          }
      UnscoredSearchResults(count, results.map(UnscoredResultEntry(_)))
    }
  }

}
