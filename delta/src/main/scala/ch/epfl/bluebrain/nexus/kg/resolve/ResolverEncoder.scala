package ch.epfl.bluebrain.nexus.kg.resolve

import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node._
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.{Graph, GraphEncoder}
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig

/**
  * Encoders for [[Resolver]]
  */
object ResolverEncoder {

  implicit def resolverGraphEncoder(implicit http: HttpConfig): GraphEncoder[Resolver] =
    GraphEncoder {
      case r: InProjectResolver                                                    => Graph(r.id, r.mainTriples(nxv.InProject.value))
      case r @ CrossProjectResolver(resTypes, projects, identities, _, _, _, _, _) =>
        val triples = r.mainTriples(nxv.CrossProject.value) ++ r.triplesFor(identities) ++ r.triplesFor(resTypes)
        Graph(r.id, triples).append(nxv.projects, GraphEncoder[List[ProjectIdentifier]].apply(projects))
    }

  implicit private class ResolverSyntax(resolver: Resolver) {
    private val s = IriNode(resolver.id)

    def mainTriples(tpe: AbsoluteIri): Set[Triple] =
      Set(
        (s, rdf.tpe, nxv.Resolver),
        (s, rdf.tpe, tpe),
        (s, nxv.priority, resolver.priority),
        (s, nxv.deprecated, resolver.deprecated),
        (s, nxv.rev, resolver.rev)
      )

    def triplesFor(identities: Set[Identity])(implicit http: HttpConfig): Set[Triple] =
      identities.foldLeft(Set.empty[Triple]) { (acc, identity) =>
        val (identityId, triples) = triplesFor(identity)
        acc + ((s, nxv.identities, identityId)) ++ triples
      }

    def triplesFor(resourceTypes: Set[AbsoluteIri]): Set[Triple] =
      resourceTypes.map(r => (s, nxv.resourceTypes, IriNode(r)): Triple)

    private def triplesFor(identity: Identity)(implicit http: HttpConfig): (IriNode, Set[Triple]) = {
      val ss = IriNode(identity.id)
      identity match {
        case User(sub, realm)     => ss -> Set((ss, rdf.tpe, nxv.User), (ss, nxv.realm, realm), (ss, nxv.subject, sub))
        case Group(group, realm)  => ss -> Set((ss, rdf.tpe, nxv.Group), (ss, nxv.realm, realm), (ss, nxv.group, group))
        case Authenticated(realm) => ss -> Set((ss, rdf.tpe, nxv.Authenticated), (ss, nxv.realm, realm))
        case _                    => ss -> Set((ss, rdf.tpe, nxv.Anonymous))
      }
    }
  }
}
