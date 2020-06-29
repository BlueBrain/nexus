package ch.epfl.bluebrain.nexus.kg.indexing

import cats.syntax.show._
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Projection, Source}
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node._
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphEncoder, Node}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig

/**
  * Encoders for [[View]]
  */
object ViewEncoder {

  private def triples(view: SparqlView) =
    filterTriples(view.id, view.filter) + metadataTriple(view.id, view.includeMetadata)

  private def triples(view: ElasticSearchView) =
    Set[Triple](
      metadataTriple(view.id, view.includeMetadata),
      (view.id, nxv.mapping, view.mapping.noSpaces),
      (view.id, nxv.sourceAsText, view.sourceAsText)
    ) ++ filterTriples(view.id, view.filter)

  implicit def viewGraphEncoder(implicit http: HttpConfig): GraphEncoder[View] =
    GraphEncoder.instance {
      case view: ElasticSearchView                                                         =>
        Graph(view.id, triples(view) ++ view.mainTriples(nxv.ElasticSearchView.value))

      case view: SparqlView                                                                =>
        Graph(view.id, triples(view) ++ view.mainTriples(nxv.SparqlView.value))

      case view: AggregateElasticSearchView                                                =>
        Graph(view.id, view.mainTriples(nxv.AggregateElasticSearchView.value) ++ view.triplesForView(view.value))

      case view: AggregateSparqlView                                                       =>
        Graph(view.id, view.mainTriples(nxv.AggregateSparqlView.value) ++ view.triplesForView(view.value))

      case composite @ CompositeView(sources, projections, rebuildStrategy, _, _, _, _, _) =>
        val sourcesTriples     = sources.foldLeft(Set.empty[Triple]) { (acc, source) =>
          val sourceCommon = sourceCommons(composite.id, source)
          source match {
            case ProjectEventStream(id, _)                                         =>
              acc ++ sourceCommon + ((id, rdf.tpe, nxv.ProjectEventStream))
            case CrossProjectEventStream(id, _, projectIdentifier, identities)     =>
              acc ++ sourceCommon ++ identitiesTriples(id, identities) + ((id, rdf.tpe, nxv.CrossProjectEventStream)) +
                ((id, nxv.project, projectIdentifier.show))
            case RemoteProjectEventStream(id, _, projectLabel, endpoint, tokenOpt) =>
              acc ++ sourceCommon ++ Set[Triple](
                (id, rdf.tpe, nxv.RemoteProjectEventStream),
                (id, nxv.project, projectLabel.show),
                (id, nxv.endpoint, endpoint)
              ) ++ tokenOpt.map(token => (id, nxv.token, token.value): Triple)
          }
        }
        val rebuildTriples     = rebuildStrategy
          .map { interval =>
            val node = Node.blank
            Set[Triple](
              (composite.id, nxv.rebuildStrategy, node),
              (node, rdf.tpe, nxv.Interval),
              (node, nxv.value, interval.value.toString())
            )
          }
          .getOrElse(Set.empty[Triple])
        val projectionsTriples = projections.flatMap {
          case Projection.ElasticSearchProjection(query, view, context) =>
            val node: IriNode = view.id
            Set[Triple](
              (composite.id, nxv.projections, node),
              (node, rdf.tpe, nxv.ElasticSearchProjection),
              (node, nxv.query, query),
              (node, nxv.uuid, view.uuid.toString),
              (node, nxv.context, context.noSpaces)
            ) ++ triples(view)
          case Projection.SparqlProjection(query, view)                 =>
            val node: IriNode = view.id
            Set[Triple](
              (composite.id, nxv.projections, node),
              (node, rdf.tpe, nxv.SparqlProjection),
              (node, nxv.query, query),
              (node, nxv.uuid, view.uuid.toString)
            ) ++ triples(view)
        }
        Graph(
          composite.id,
          composite.mainTriples(
            nxv.CompositeView.value,
            nxv.Beta.value
          ) ++ sourcesTriples ++ projectionsTriples ++ rebuildTriples
        )
    }

  private def sourceCommons(s: IriOrBNode, source: Source): Set[Triple] =
    Set[Triple]((s, nxv.sources, source.id)) ++ filterTriples(source.id, source.filter)

  private def filterTriples(s: IriOrBNode, filter: Filter): Set[Triple] =
    filter.resourceSchemas.map(r => (s, nxv.resourceSchemas, r): Triple) ++
      filter.resourceTypes.map(r => (s, nxv.resourceTypes, r): Triple) +
      ((s, nxv.includeDeprecated, filter.includeDeprecated): Triple) ++
      filter.resourceTag.map(resourceTag => (s, nxv.resourceTag, resourceTag): Triple)

  private def metadataTriple(s: IriOrBNode, includeMetadata: Boolean): Triple =
    (s, nxv.includeMetadata, includeMetadata)

  def identitiesTriples(s: IriOrBNode, identities: Set[Identity])(implicit http: HttpConfig): Set[Triple] =
    identities.foldLeft(Set.empty[Triple]) { (acc, identity) =>
      val (identityId, triples) = identityTriples(identity)
      acc + ((s, nxv.identities, identityId)) ++ triples
    }

  private def identityTriples(identity: Identity)(implicit http: HttpConfig): (IriNode, Set[Triple]) = {
    val ss = IriNode(identity.id)
    identity match {
      case User(sub, realm)     => ss -> Set((ss, rdf.tpe, nxv.User), (ss, nxv.realm, realm), (ss, nxv.subject, sub))
      case Group(group, realm)  => ss -> Set((ss, rdf.tpe, nxv.Group), (ss, nxv.realm, realm), (ss, nxv.group, group))
      case Authenticated(realm) => ss -> Set((ss, rdf.tpe, nxv.Authenticated), (ss, nxv.realm, realm))
      case _                    => ss -> Set((ss, rdf.tpe, nxv.Anonymous))
    }
  }

  implicit private class ViewSyntax(view: View) {
    private val s = IriNode(view.id)

    def mainTriples(tpe: AbsoluteIri*): Set[Triple] =
      Set[Triple](
        (s, rdf.tpe, nxv.View),
        (s, nxv.uuid, view.uuid.toString),
        (s, nxv.deprecated, view.deprecated),
        (s, nxv.rev, view.rev)
      ) ++ tpe.map(t => (s, rdf.tpe, t): Triple).toSet

    def triplesForView(views: Set[ViewRef]): Set[Triple] =
      views.flatMap { viewRef =>
        val ss = blank
        Set[Triple]((s, nxv.views, ss), (ss, nxv.viewId, viewRef.id), (ss, nxv.project, viewRef.project.show))
      }

  }
}
