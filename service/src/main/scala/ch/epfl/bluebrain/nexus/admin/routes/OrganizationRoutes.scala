package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.{HttpConfig, PaginationConfig}
import ch.epfl.bluebrain.nexus.admin.config.Permissions.orgs
import ch.epfl.bluebrain.nexus.admin.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.admin.directives.{AuthDirectives, QueryDirectives}
import ch.epfl.bluebrain.nexus.admin.index.OrganizationCache
import ch.epfl.bluebrain.nexus.admin.marshallers.instances._
import ch.epfl.bluebrain.nexus.admin.organizations.{Organization, Organizations}
import ch.epfl.bluebrain.nexus.admin.routes.OrganizationRoutes._
import ch.epfl.bluebrain.nexus.admin.types.ResourceF._
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import monix.eval.Task
import monix.execution.Scheduler

class OrganizationRoutes(organizations: Organizations[Task])(
    implicit ic: IamClient[Task],
    cache: OrganizationCache[Task],
    icc: IamClientConfig,
    hc: HttpConfig,
    pc: PaginationConfig,
    s: Scheduler
) extends AuthDirectives(ic)
    with QueryDirectives {

  def routes: Route = (pathPrefix("orgs") & extractToken) { implicit token =>
    concat(
      // fetch
      (get & org & pathEndOrSingleSlash & parameter("rev".as[Long].?)) { (orgLabel, optRev) =>
        authorizeOn(pathOf(orgLabel), orgs.read).apply {
          traceOne {
            complete(organizations.fetch(orgLabel, optRev).runNotFound)
          }
        }
      },
      // writes
      extractSubject.apply { implicit subject =>
        concat(
          (org & pathEndOrSingleSlash) {
            orgLabel =>
              traceOne {
                concat(
                  // deprecate
                  (delete & parameter("rev".as[Long]) & authorizeOn(pathOf(orgLabel), orgs.write)) { rev =>
                    complete(organizations.deprecate(orgLabel, rev).runToFuture)
                  },
                  // update
                  (put & parameter("rev".as[Long]) & authorizeOn(pathOf(orgLabel), orgs.write)) { rev =>
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
              (put & authorizeOn(pathOf(orgLabel), orgs.create)) {
                entity(as[OrganizationDescription]) { org =>
                  complete(organizations.create(Organization(orgLabel, org.description)).runWithStatus(Created))
                } ~
                  complete(organizations.create(Organization(orgLabel, None)).runWithStatus(Created))
              }
            }
          }
        )
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
  private[routes] final case class OrganizationDescription(description: Option[String])

  private[routes] implicit val descriptionDecoder: Decoder[OrganizationDescription] =
    deriveDecoder[OrganizationDescription]

  def apply(organizations: Organizations[Task])(
      implicit ic: IamClient[Task],
      cache: OrganizationCache[Task],
      icc: IamClientConfig,
      hc: HttpConfig,
      pagination: PaginationConfig,
      s: Scheduler
  ): OrganizationRoutes =
    new OrganizationRoutes(organizations)
}
