package ch.epfl.bluebrain.nexus.iam.realms

import java.time.Instant
import java.util
import java.util.Date

import akka.http.scaladsl.model.HttpRequest
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.GroupsSpec._
import ch.epfl.bluebrain.nexus.iam.realms.WellKnownSpec.{userInfoUrl, _}
import ch.epfl.bluebrain.nexus.iam.types.Identity.Group
import ch.epfl.bluebrain.nexus.iam.types.Label
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.delta.config.Settings
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.{StateMachineConfig => GroupsConfig}
import ch.epfl.bluebrain.nexus.util._
import com.nimbusds.jwt.JWTClaimsSet
import io.circe.Json
import io.circe.parser._
import org.mockito.ArgumentMatchersSugar.isA
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

//noinspection TypeAnnotation
class GroupsSpec
    extends ActorSystemFixture("GroupsSpec", true)
    with Matchers
    with IOEitherValues
    with IOOptionValues
    with Randomness
    with IdiomaticMockito {

  val config = Settings(system).appConfig

  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  implicit val gc: GroupsConfig = config.groups

  def httpClient(response: Json): HttpJsonClient[IO] = {
    val m = mock[HttpJsonClient[IO]]
    m.apply(isA[HttpRequest]) shouldReturn IO.pure(response)
    m
  }

  def token: AccessToken =
    RealmsSpec.token(genString())

  val ar: ActiveRealm = ActiveRealm(
    Label("realm").rightValue,
    "The Realm",
    openIdUrl,
    issuer,
    grantTypes,
    None,
    authorizationUrl,
    tokenUrl,
    userInfoUrl,
    Some(revocationUrl),
    Some(endSessionUrl),
    Set(publicKeyJson)
  )

  def csb: JWTClaimsSet.Builder =
    new JWTClaimsSet.Builder()
      .subject("sub")
      .expirationTime(Date.from(Instant.now().plusSeconds(3600)))

  "A Groups" should {
    "correctly extract the groups" when {
      val expected = Set("one", "two", "three").map(Group(_, "realm"))
      "the claimset contains comma separated group values" in {
        val claims      = csb
          .claim("groups", "one, two ,three,")
          .build()
        implicit val hc = httpClient(userinfoNoGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual expected
      }

      "the claimset contains array group values" in {
        val claims      = csb
          .claim("groups", util.Arrays.asList("one", " two ", "three", ""))
          .build()
        implicit val hc = httpClient(userinfoNoGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual expected
      }

      "the claimset contains no group values" in {
        val claims      = csb.build()
        implicit val hc = httpClient(userinfoNoGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual Set.empty[Group]
      }

      "the claimset contains illegal groups key" in {
        val claims      = csb.claim("groups", false).build()
        implicit val hc = httpClient(userinfoNoGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual Set.empty[Group]
      }

      "the userinfo contains comma separated group values" in {
        val claims      = csb.build()
        implicit val hc = httpClient(userinfoCsvGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual expected
      }

      "the userinfo contains array group values" in {
        val claims      = csb.build()
        implicit val hc = httpClient(userinfoArrayGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual expected
      }

      "the userinfo contains illegal groups key" in {
        val claims      = csb.build()
        implicit val hc = httpClient(userinfoWrongGroups)
        Groups[IO]().ioValue.groups(token, claims, ar, None).ioValue shouldEqual Set.empty[Group]
      }
    }
  }

}

//noinspection TypeAnnotation
object GroupsSpec {
  import ch.epfl.bluebrain.nexus.util.EitherValues._

  val userInfoUrl = Url("https://localhost/auth/userinfo").rightValue

  val userinfoArrayGroupsString =
    """{
      |  "sub": "somesub",
      |  "email_verified": false,
      |  "name": "Full Name",
      |  "groups": [
      |    "one",
      |    " two ",
      |    "three",
      |    ""
      |  ],
      |  "preferred_username": "preferred",
      |  "given_name": "Full",
      |  "family_name": "Name",
      |  "email": "noreply@epfl.ch"
      |}
    """.stripMargin
  val userinfoArrayGroups       = parse(userinfoArrayGroupsString).rightValue

  val userinfoCsvGroupsString =
    """{
      |  "sub": "somesub",
      |  "email_verified": false,
      |  "name": "Full Name",
      |  "groups": "one, two ,three,",
      |  "preferred_username": "preferred",
      |  "given_name": "Full",
      |  "family_name": "Name",
      |  "email": "noreply@epfl.ch"
      |}
    """.stripMargin
  val userinfoCsvGroups       = parse(userinfoCsvGroupsString).rightValue

  val userinfoNoGroupsString =
    """{
      |  "sub": "somesub",
      |  "email_verified": false,
      |  "name": "Full Name",
      |  "preferred_username": "preferred",
      |  "given_name": "Full",
      |  "family_name": "Name",
      |  "email": "noreply@epfl.ch"
      |}
    """.stripMargin
  val userinfoNoGroups       = parse(userinfoNoGroupsString).rightValue

  val userinfoWrongGroupsString =
    """{
      |  "sub": "somesub",
      |  "email_verified": false,
      |  "name": "Full Name",
      |  "preferred_username": "preferred",
      |  "groups": false,
      |  "given_name": "Full",
      |  "family_name": "Name",
      |  "email": "noreply@epfl.ch"
      |}
    """.stripMargin
  val userinfoWrongGroups       = parse(userinfoWrongGroupsString).rightValue
}
