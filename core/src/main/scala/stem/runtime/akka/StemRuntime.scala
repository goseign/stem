package stem.runtime.akka

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.ask
import akka.util.Timeout
import izumi.reflect.Tag
import scodec.bits.BitVector
import stem.runtime.{BaseAlgebraCombinators, StemProtocol}
import stem.runtime.LiveBaseAlgebraCombinators.memory
import stem.runtime.akka.serialization.Message
import stem.tagging.Tagging
import zio.{Has, Runtime, Task, ZEnv, ZIO}

import scala.concurrent.duration.{Duration, FiniteDuration}

object StemRuntime {

  case class KeyedCommand(key: String, bytes: BitVector) extends Message

  def memoryStemtity[Key: KeyDecoder: KeyEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    tagging: Tagging[Key],
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject]
  )(
    implicit runtime: Runtime[ZEnv],
    protocol: StemProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[ActorSystem] with Has[RuntimeSettings], Throwable, Key => Algebra] = {
    for {
      combinators <- memory[Key, State, Event, Reject](tagging)
      ledger      <- buildStemtity(typeName, eventSourcedBehaviour, combinators)
    } yield ledger
  }

  def buildStemtity[Key: KeyDecoder: KeyEncoder: Tag, Algebra, State: Tag, Event: Tag, Reject: Tag](
    typeName: String,
    eventSourcedBehaviour: EventSourcedBehaviour[Algebra, State, Event, Reject],
    baseAlgebraCombinators: BaseAlgebraCombinators[Key, State, Event, Reject]
  )(
    implicit runtime: Runtime[ZEnv],
    protocol: StemProtocol[Algebra, State, Event, Reject]
  ): ZIO[Has[ActorSystem] with Has[RuntimeSettings], Throwable, Key => Algebra] = ZIO.access { layer =>
    val system = layer.get[ActorSystem]
    val settings = layer.get[RuntimeSettings]
    val props = StemActor.props[Key, Algebra, State, Event, Reject](eventSourcedBehaviour, baseAlgebraCombinators)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case KeyedCommand(entityId, c) =>
        (entityId, CommandInvocation(c))
    }

    val numberOfShards = settings.numberOfShards

    val extractShardId: ShardRegion.ExtractShardId = {
      case KeyedCommand(key, _) =>
        String.valueOf(scala.math.abs(key.hashCode) % numberOfShards)
      case other => throw new IllegalArgumentException(s"Unexpected message [$other]")
    }

    val shardRegion = ClusterSharding(system).start(
      typeName = typeName,
      entityProps = props,
      settings = settings.clusterShardingSettings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId
    )

    val keyEncoder = KeyEncoder[Key]

    // macro that creates bytes when method is invoked

    key: Key => {

      implicit val askTimeout: Timeout = Timeout(settings.askTimeout)
      // implementation of algebra that transform the method in bytes inject the function in it
      protocol.client(
//      RpcMacro.client[Algebra, Reject](
        { bytes =>
          Task
            .fromFuture { _ =>
              shardRegion ? KeyedCommand(keyEncoder(key), bytes)
            }
            .flatMap {
              case result: CommandResult =>
                Task.succeed(result.bytes)
              case other =>
                Task.fail(
                  new IllegalArgumentException(s"Unexpected response [$other] from shard region")
                )
            }
        },
        eventSourcedBehaviour.errorHandler
      )
    }
  }

}

final case class RuntimeSettings(
  numberOfShards: Int,
  idleTimeout: FiniteDuration,
  askTimeout: FiniteDuration,
  clusterShardingSettings: ClusterShardingSettings
)

object RuntimeSettings {

  /**
    * Reads config from `stem.akka-runtime`, see stem.conf for details
    * @param system Actor system to get config from
    * @return default settings
    */
  def default(system: ActorSystem): RuntimeSettings = {
    val config = system.settings.config.getConfig("stem.akka-runtime")
    def getMillisDuration(path: String): FiniteDuration =
      Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

    RuntimeSettings(
      config.getInt("number-of-shards"),
      getMillisDuration("idle-timeout"),
      getMillisDuration("ask-timeout"),
      ClusterShardingSettings(system)
    )
  }
}