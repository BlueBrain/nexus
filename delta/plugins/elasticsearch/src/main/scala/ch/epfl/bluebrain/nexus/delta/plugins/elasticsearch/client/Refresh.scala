package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

sealed trait Refresh {
  def value: String
}

object Refresh {
  case object True extends Refresh {
    override def value: String = "true"
  }

  case object False extends Refresh {
    override def value: String = "false"
  }

  case object WaitFor extends Refresh {
    override def value: String = "wait_for"
  }
}
