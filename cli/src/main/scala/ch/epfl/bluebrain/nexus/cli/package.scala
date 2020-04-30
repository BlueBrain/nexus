package ch.epfl.bluebrain.nexus

import ch.epfl.bluebrain.nexus.cli.CliError.ClientError

package object cli {

  type ClientErrOr[A] = Either[ClientError, A]

}
