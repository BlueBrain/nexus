package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.{HttpCredentials, OAuth2BearerToken}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken

trait HttpRequestSyntax {
  implicit final def httpRequestSyntax(req: HttpRequest): HttpRequestOpts = new HttpRequestOpts(req)
}

final class HttpRequestOpts(private val req: HttpRequest) {

  /**
    * Add the [[OAuth2BearerToken]] to the current request when the implicit optional [[AuthToken]] is present
    */
  def withCredentials(implicit cred: Option[AuthToken]): HttpRequest =
    withHttpCredentials(cred.map(token => OAuth2BearerToken(token.value)))

  /**
    * Add the [[HttpCredentials]] to the current request when the passed value is some
    */
  def withHttpCredentials(implicit cred: Option[HttpCredentials]): HttpRequest =
    cred.fold(req)(req.addCredentials)

}
