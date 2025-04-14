package ch.epfl.bluebrain.nexus.delta.sdk.utils

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.{Identities, IdentitiesDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}

trait RouteFixtureBuilders {
  self: BaseRouteSpec =>

  final protected def as(user: User): RequestTransformer = addCredentials(OAuth2BearerToken(user.subject))

  final protected def usersFixture(users: (User, AclAddress, Set[Permission])*): (AclSimpleCheck, Identities) = {
    AclSimpleCheck(
      users*
    ).accepted -> IdentitiesDummy(users.map { case (user, _, _) =>
      Caller(user, Set(user, Anonymous, Authenticated(user.realm), Group("group", user.realm)))
    }*)
  }
}
