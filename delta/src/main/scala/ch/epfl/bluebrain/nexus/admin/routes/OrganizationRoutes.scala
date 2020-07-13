package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import ch.epfl.bluebrain.nexus.admin.config.Permissions.orgs
import ch.epfl.bluebrain.nexus.admin.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.admin.directives.QueryDirectives
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, Organizations}
import ch.epfl.bluebrain.nexus.admin.routes.OrganizationRoutes._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF._
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.delta.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.marshallers.instances._
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import monix.eval.Task
import monix.execution.Scheduler

class OrganizationRoutes(
    organizations: Organizations[Task],
    cache: OrganizationCache[Task],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit
    hc: HttpConfig,
    pc: PaginationConfig,
    s: Scheduler
) extends AuthDirectives(acls, realms)
    with QueryDirectives {

  implicit val oc: OrganizationCache[Task] = cache
  def routes: Route                        =
    (pathPrefix("orgs") & extractCaller) { implicit caller =>
      implicit val subject: Subject = caller.subject
      concat(
        // fetch
        (get & org & pathEndOrSingleSlash & parameter("rev".as[Long].?)) { (orgLabel, optRev) =>
          authorizeFor(pathOf(orgLabel), orgs.read)(caller) {
            traceOne {
              complete(organizations.fetch(orgLabel, optRev).runNotFound)
            }
          }
        },
        // writes
        (org & pathEndOrSingleSlash) { orgLabel =>
          traceOne {
            concat(
              // deprecate
              (delete & parameter("rev".as[Long]) & authorizeFor(pathOf(orgLabel), orgs.write)) { rev =>
                complete(organizations.deprecate(orgLabel, rev).runToFuture)
              },
              // update
              (put & parameter("rev".as[Long]) & authorizeFor(pathOf(orgLabel), orgs.write)) { rev =>
                entity(as[OrganizationDescription]) { org =>
                  complete(organizations.update(orgLabel, Organization(orgLabel, org.description), rev).runToFuture)
                } ~
                  complete(organizations.update(orgLabel, Organization(orgLabel, None), rev).runToFuture)
              }
            )
          }
        },
        // create
        (pathPrefix(Segment) & pathEndOrSingleSlash) { orgLabel =>
          traceOne {
            (put & authorizeFor(pathOf(orgLabel), orgs.create)) {
              entity(as[OrganizationDescription]) { org =>
                complete(organizations.create(Organization(orgLabel, org.description)).runWithStatus(Created))
              } ~
                complete(organizations.create(Organization(orgLabel, None)).runWithStatus(Created))
            }
          }
        },
        // listing
        (get & pathEndOrSingleSlash & paginated & searchParamsOrgs & extractCallerAcls(anyOrg)) {
          (pagination, params, acls) =>
            traceCol {
              complete(organizations.list(params, pagination)(acls).runToFuture)
            }
        }
      )
    }

  private def pathOf(orgLabel: String): Path = {
    import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
    Segment(orgLabel, Path./)
  }

  private def traceCol: Directive0 =
    operationName(s"/${hc.prefix}/orgs")

  private def traceOne: Directive0 =
    operationName(s"/${hc.prefix}/orgs/{}")
}

object OrganizationRoutes {

  /**
    * Organization payload for creation and update requests.
    *
    * @param description an optional description
    */
  final private[routes] case class OrganizationDescription(description: Option[String])

  implicit private[routes] val descriptionDecoder: Decoder[OrganizationDescription] =
    deriveDecoder[OrganizationDescription]
}
