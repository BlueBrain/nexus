package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Directives.rawPathPrefix
import akka.http.scaladsl.server.{Directive0, PathMatcher}

import scala.annotation.tailrec

/**
  * Collection of custom directives for matching against prefix paths.
  */
trait PrefixDirectives {

  final def stripTrailingSlashes(path: Path): Path = {
    @tailrec
    def strip(p: Path): Path =
      p match {
        case Path.Empty       => Path.Empty
        case Path.Slash(rest) => strip(rest)
        case other            => other
      }
    strip(path.reverse).reverse
  }

  /**
    * Creates a path matcher from the argument ''uri'' by stripping the slashes at the end of its path.  The matcher
    * is applied directly to the prefix of the unmatched path.
    *
    * @param uri the uri to use as a prefix
    */
  final def uriPrefix(uri: Uri): Directive0 =
    rawPathPrefix(PathMatcher(stripTrailingSlashes(uri.path), ()))
}

/**
  * Collection of custom directives for matching against prefix paths.
  */
object PrefixDirectives extends PrefixDirectives
