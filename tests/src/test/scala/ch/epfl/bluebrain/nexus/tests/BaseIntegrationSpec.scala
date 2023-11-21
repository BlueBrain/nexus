package ch.epfl.bluebrain.nexus.tests

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.testkit._
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsEffectAsyncScalaTestAdapter, CatsIOValues}
import ch.epfl.bluebrain.nexus.testkit.scalatest.{ClasspathResources, EitherValues, ScalaTestExtractValue}
import ch.epfl.bluebrain.nexus.tests.BaseIntegrationSpec._
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity._
import ch.epfl.bluebrain.nexus.tests.admin.AdminDsl
import ch.epfl.bluebrain.nexus.tests.config.ConfigLoader._
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Organizations
import ch.epfl.bluebrain.nexus.tests.iam.{AclDsl, PermissionDsl}
import ch.epfl.bluebrain.nexus.tests.kg.{ElasticSearchViewsDsl, KgDsl}
import com.typesafe.config.ConfigFactory
import io.circe.Json
import org.scalactic.source.Position
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.concurrent.duration._

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
    with ScalaFutures {

  private val logger = Logger[this.type]

  implicit val config: TestsConfig = load[TestsConfig](ConfigFactory.load(), "tests")

  val deltaUrl: Uri = Uri(s"http://${sys.props.getOrElse("delta-url", "localhost:8080")}/v1")

  private[tests] val deltaClient = HttpClient(deltaUrl)

  val elasticsearchDsl = new ElasticsearchDsl()
  val blazegraphDsl    = new BlazegraphDsl()
  val keycloakDsl      = new KeycloakDsl()

  val aclDsl                = new AclDsl(deltaClient)
  val permissionDsl         = new PermissionDsl(deltaClient)
  val adminDsl              = new AdminDsl(deltaClient, config)
  val kgDsl                 = new KgDsl(config)
  val elasticsearchViewsDsl = new ElasticSearchViewsDsl(deltaClient)

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(config.patience, 300.millis)

  def eventually(io: IO[Assertion])(implicit pos: Position): Assertion =
    eventually { io.unsafeRunSync() }

  def runIO[A](io: IO[A]): Assertion = io.map { _ => succeed }.unsafeRunSync()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- elasticsearchDsl.createTemplate()
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
                  "/iam/realms/create.json",
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

  /**
    * Create projects and the parent organization for the provided user
    */
  def createProjects(user: Authenticated, org: String, projects: String*): IO[Unit] =
    for {
      _ <- aclDsl.addPermission("/", user, Organizations.Create)
      _ <- adminDsl.createOrganization(org, org, user, ignoreConflict = true)
      _ <- projects.toList.traverse { project =>
             val projectRef = s"$org/$project"
             kgDsl.projectJson(name = projectRef).flatMap(adminDsl.createProject(org, project, _, user))
           }
    } yield ()

  private[tests] def dispositionType(response: HttpResponse): ContentDispositionType =
    response.header[`Content-Disposition`].value.dispositionType

  private[tests] def attachmentName(response: HttpResponse): String =
    response
      .header[`Content-Disposition`]
      .value
      .params
      .get("filename")
      .value

  private[tests] def contentType(response: HttpResponse): ContentType =
    response.header[`Content-Type`].value.contentType

  private[tests] def httpEncodings(response: HttpResponse): Seq[HttpEncoding] =
    response.header[`Content-Encoding`].value.encodings

  private[tests] def decodeGzip(input: ByteString): String =
    Coders.Gzip.decode(input).map(_.utf8String).futureValue

  private[tests] def genId(length: Int = 15): String =
    genString(length = length, Vector.range('a', 'z') ++ Vector.range('0', '9'))

  private[tests] def expect[A](code: StatusCode) = (_: A, response: HttpResponse) => response.status shouldEqual code

  private[tests] def expectCreated[A] = expect(StatusCodes.Created)

  private[tests] def expectNotFound[A] = expect(StatusCodes.NotFound)

  private[tests] def expectForbidden[A]  = expect(StatusCodes.Forbidden)
  private[tests] def expectBadRequest[A] = expect(StatusCodes.BadRequest)

  private[tests] def expectOk[A] = expect(StatusCodes.OK)

  private[tests] def tag(name: String, rev: Int) = json"""{"tag": "$name", "rev": $rev}"""
}

object BaseIntegrationSpec {

  val setupCompleted: Ref[IO, Boolean] = Ref.unsafe(false)

}
