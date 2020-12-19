package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken

trait HttpRequestSyntax {
  implicit final def httpRequestSyntax(req: HttpRequest): HttpRequestOpts = new HttpRequestOpts(req)
}

final class HttpRequestOpts(private val req: HttpRequest) {

  /**
    * Add the [[OAuth2BearerToken]] to the current request when the implicit optional [[AuthToken]] is present
    */
  def withCredentials(implicit cred: Option[AuthToken]): HttpRequest =
    cred.fold(req)(token => req.addCredentials(OAuth2BearerToken(token.value)))
}
