package ai.senscience.nexus.tests

import ai.senscience.nexus.tests.BaseIntegrationSpec.*
import ai.senscience.nexus.tests.HttpClient.*
import ai.senscience.nexus.tests.Identity.*
import ai.senscience.nexus.tests.admin.{AdminDsl, ProjectPayload}
import ai.senscience.nexus.tests.config.ConfigLoader.*
import ai.senscience.nexus.tests.config.TestsConfig
import ai.senscience.nexus.tests.iam.types.Permission
import ai.senscience.nexus.tests.iam.types.Permission.Organizations
import ai.senscience.nexus.tests.iam.{AclDsl, PermissionDsl}
import ai.senscience.nexus.tests.kg.ElasticSearchViewsDsl
import ai.senscience.nexus.tests.kg.VersionSpec.VersionBundle
import ai.senscience.nexus.tests.kg.files.StoragesDsl
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.*
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.testkit.*
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsEffectAsyncScalaTestAdapter, CatsEffectEventually, CatsIOValues}
import ch.epfl.bluebrain.nexus.testkit.scalatest.{ClasspathResources, EitherValues, ScalaTestExtractValue}
import com.typesafe.config.ConfigFactory
import io.circe.Json
import org.scalactic.source.Position
import org.scalatest.*
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.concurrent.duration.*

trait BaseIntegrationSpec
    extends AsyncWordSpecLike
    with ScalaTestExtractValue
    with CatsEffectAsyncScalaTestAdapter
    with ClasspathResources
    with Generators
    with Matchers
    with EitherValues
    with OptionValues
    with Inspectors
    with CatsIOValues
    with FixedClock
    with CirceUnmarshalling
    with CirceLiteral
    with CirceEq
    with BeforeAndAfterAll
    with HandleBarsFixture
    with SelfFixture
    with ScalatestRouteTest
    with Eventually
    with AppendedClues
    with ScalaFutures
    with CatsEffectEventually {

  private val logger = Logger[this.type]

  implicit val config: TestsConfig = load[TestsConfig](ConfigFactory.load(), "tests")

  val deltaUrl: Uri = Uri(s"http://${sys.props.getOrElse("delta-url", "localhost:8080")}/v1")

  val deltaClient: HttpClient = HttpClient(deltaUrl)

  lazy val isBlazegraph: Boolean = deltaClient
    .getJson[VersionBundle]("/version", Identity.ServiceAccount)
    .map { version =>
      version.dependencies.blazegraph.isDefined
    }
    .accepted

  val keycloakDsl      = new KeycloakDsl()
  val elasticsearchDsl = new ElasticsearchDsl()
  lazy val sparqlDsl   = new SparqlDsl(isBlazegraph)

  val aclDsl                = new AclDsl(deltaClient)
  val permissionDsl         = new PermissionDsl(deltaClient)
  val adminDsl              = new AdminDsl(deltaClient, config)
  val elasticsearchViewsDsl = new ElasticSearchViewsDsl(deltaClient)
  val storagesDsl           = new StoragesDsl(deltaClient)

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(config.patience, 100.millis)

  def eventually(io: IO[Assertion])(implicit pos: Position): Assertion =
    eventually { io.unsafeRunSync() }

  def runIO[A](io: IO[A]): Assertion = io.map { _ => succeed }.unsafeRunSync()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- initRealm(
             Realm.internal,
             Identity.Anonymous,
             Identity.ServiceAccount,
             Nil
           )
      _ <- aclDsl.addPermissions(
             "/",
             Identity.ServiceAccount,
             Permission.minimalPermissions
           )
      _ <- initRealm(
             testRealm,
             Identity.ServiceAccount,
             testClient,
             allUsers
           )
    } yield ()

    val allTasks = for {
      isSetupCompleted <- setupCompleted.get
      _                <- IO.unlessA(isSetupCompleted)(setup)
      _                <- setupCompleted.set(true)
      _                <- aclDsl.cleanAclsAnonymous
    } yield ()

    allTasks.unsafeRunSync()
  }

  override def afterAll(): Unit =
    IO.whenA(config.cleanUp)(elasticsearchDsl.deleteAllIndices().void).unsafeRunSync()

  protected def toAuthorizationHeader(token: String) =
    Authorization(HttpCredentials.createOAuth2BearerToken(token))

  private[tests] def authenticateUser(user: UserCredentials, client: ClientCredentials): IO[Unit] =
    for {
      token <- keycloakDsl.userToken(user, client)
      _     <- logger.info(s"Token for user ${user.name} is: $token")
      _     <- IO(tokensMap.put(user, toAuthorizationHeader(token)))
    } yield ()

  private[tests] def authenticateClient(client: ClientCredentials): IO[Unit] = {
    keycloakDsl.serviceAccountToken(client).map { token =>
      tokensMap.put(client, toAuthorizationHeader(token))
      ()
    }
  }

  /**
    * Init a new realm both in Keycloak and in Delta and Retrieve tokens for the new clients and users
    *
    * @param realm
    *   the name of the realm to create
    * @param identity
    *   the identity responsible of creating the realm in delta
    * @param client
    *   the service account to create for the realm
    * @param users
    *   the users to create in the realm
    * @return
    */
  def initRealm(
      realm: Realm,
      identity: Identity,
      client: ClientCredentials,
      users: List[UserCredentials]
  ): IO[Unit] = {
    def createRealmInDelta: IO[Assertion] =
      deltaClient.get[Json](s"/realms/${realm.name}", identity) { (json, response) =>
        runIO {
          response.status match {
            case StatusCodes.NotFound                   =>
              val body =
                jsonContentOf(
                  "iam/realms/create.json",
                  "realm" -> s"${config.realmSuffix(realm)}"
                )
              for {
                _ <- logger.info(s"Realm ${realm.name} is absent, we create it")
                _ <- deltaClient.put[Json](s"/realms/${realm.name}", body, identity) { expectCreated }
                _ <- deltaClient.get[Json](s"/realms/${realm.name}", Identity.ServiceAccount) { expectOk }
              } yield ()
            case StatusCodes.Forbidden | StatusCodes.OK =>
              for {
                _ <- logger.info(s"Realm ${realm.name} has already been created, we got status ${response.status}")
                _ <- deltaClient.get[Json](s"/realms/${realm.name}", Identity.ServiceAccount) { expectOk }
              } yield ()
            case s                                      =>
              IO(fail(s"$s wasn't expected here and we got this response: $json"))
          }
        }
      }

    for {
      // Create the realm in Keycloak
      _ <- keycloakDsl.importRealm(realm, client, users)
      // Get the tokens and cache them in the map
      _ <- users.parTraverse { user => authenticateUser(user, client) }
      _ <- authenticateClient(client)
      // Creating the realm in delta
      _ <- logger.info(s"Creating realm ${realm.name} in the delta instance")
      _ <- createRealmInDelta
    } yield ()
  }

  def createOrg(user: Authenticated, org: String): IO[Unit] =
    for {
      _ <- aclDsl.addPermission("/", user, Organizations.Create)
      _ <- adminDsl.createOrganization(org, org, user, ignoreConflict = true)
    } yield ()

  /**
    * Create projects and the parent organization for the provided user
    */
  def createProjects(user: Authenticated, org: String, projects: String*): IO[Unit] =
    for {
      _ <- createOrg(user, org)
      _ <- projects.toList.traverse { project =>
             val payload = ProjectPayload.generate(s"$org/$project")
             adminDsl.createProject(org, project, payload, user)
           }
    } yield ()

  private[tests] def contentType(response: HttpResponse): ContentType =
    response.header[`Content-Type`].value.contentType

  private[tests] def genId(length: Int = 15): String =
    genString(length = length, Vector.range('a', 'z') ++ Vector.range('0', '9'))

  private[tests] def expect[A](code: StatusCode) = (_: A, response: HttpResponse) => response.status shouldEqual code

  private[tests] def expectCreated[A] = expect(StatusCodes.Created)

  private[tests] def expectNotFound[A] = expect(StatusCodes.NotFound)

  private[tests] def expectForbidden[A]  = expect(StatusCodes.Forbidden)
  private[tests] def expectBadRequest[A] = expect(StatusCodes.BadRequest)

  private[tests] def expectOk[A] = expect(StatusCodes.OK)

  private[tests] def tag(name: String, rev: Int) = json"""{"tag": "$name", "rev": $rev}"""

  private[tests] def `@type`(expectedType: String) = HavePropertyMatcher[Json, String] { json =>
    val actualType = Optics.`@type`.getOption(json)
    HavePropertyMatchResult(
      actualType.contains(expectedType),
      "@type",
      expectedType,
      actualType.orNull
    )
  }
}

object BaseIntegrationSpec {

  val setupCompleted: Ref[IO, Boolean] = Ref.unsafe(false)
}
