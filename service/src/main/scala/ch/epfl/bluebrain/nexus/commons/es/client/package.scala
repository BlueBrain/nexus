package ch.epfl.bluebrain.nexus.commons.es

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path.SingleSlash

import scala.annotation.tailrec

package object client {

  @tailrec
  private def append(path: String, uri: Uri): Uri =
    if (uri.path.endsWithSlash) uri.copy(path = uri.path + path)
    else append(path, uri.copy(path = uri.path ++ SingleSlash))

  private[client] implicit class UriSyntax(private val uri: Uri) extends AnyVal {
    def /(path: String): Uri = append(path, uri)
  }
}
