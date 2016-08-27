package es.weso.validator
import cats._, data._
import cats.implicits._
import simulacrum._

@typeclass trait CanLog[A] {
  def log(x: String): A
}

abstract class Checker {
  import CanLog.ops._
  type Config
  type Env
  type Err
  type Log
  implicit val envMonoid: Monoid[Env]
  implicit val logCanLog: CanLog[Log]
  implicit val logMonoid: Monoid[Log]
  
  type ReaderConfig[A] = Kleisli[Id, Config, A]
  type ReaderEC[A] = Kleisli[ReaderConfig, Env, A]
  type WriterEC[A] = WriterT[ReaderEC, Log, A] 
  type Check[A] = EitherT[WriterEC, Err, A]

  def getConfig: Check[Config] = {
    readerConfig2check(Kleisli.ask[Id, Config])
  }

  def getEnv: Check[Env] = {
    readerEC2check(Kleisli.ask[ReaderConfig, Env])
  }

  def addLog(log: Log): Check[Unit] = {
    writerEC2check(WriterT.tell[ReaderEC, Log](log))
  }
  
  def logStr(msg: String): Check[Unit] = {
    addLog(CanLog[Log].log(msg))
  }

  def local[A](f: Env => Env)(c: Check[A]): Check[A] = {
    EitherT(runLocalW(f)(c.value))
  }

  def ok[A](x: A): Check[A] =
    EitherT.pure[WriterEC, Err, A](x)
    
  def err[A](e: Err): Check[A] =
    EitherT.left[WriterEC, Err, A](mkErr[WriterEC](e))

  def orElse[A](c1: Check[A], c2: => Check[A]): Check[A] =
    c1.orElse(c2)
    
  def checkSome[A](cs: List[Check[A]], errorIfNone: Err): Check[A] = {
    lazy val z: Check[A] = err(errorIfNone)
    def comb(c1: Check[A], c2: Check[A]) = orElse(c1, c2)
    cs.foldRight(z)(comb)
  }
  
  def cond[A,B](c: Check[A], thenPart: A => Check[B], elsePart: Err => Check[B]): Check[B] = for {
    v <- MonadError[Check, Err].attempt(c)
    b <- v.fold(e => elsePart(e),v => thenPart(v))
  } yield b
  
  def checkList[A,B](ls: List[A], check: A => Check[B]): Check[List[B]] = {
    checkAll(ls.map(check))
  }
  
  def checkAll[A](xs: List[Check[A]]): Check[List[A]] =
    xs.sequence


  def validateCheck(condition: Boolean, e: Err): Check[Unit] = {
    if (condition) EitherT.pure(())
    else err(e)
  }
  
  //implicit val monadCheck = implicitly[Monad[Check]]
  lazy val mWriterEC = implicitly[Monad[WriterEC]]

  def run[A](c: Check[A])(config: Config)(env: Env = Monoid[Env].empty): (Log, Either[Err, A]) =
    c.value.run.run(env).run(config)

  def runCheck[A](c: Check[A])(config: Config)(env: Env = Monoid[Env].empty): (Either[Err, A],Log) =
    run(c)(config)(env).swap
    
  def runValue[A](c: Check[A])(config: Config)(env: Env = Monoid[Env].empty): Either[Err, A] =
    run(c)(config)(env)._2

  def runLog[A](c: Check[A])(config: Config)(env: Env = Monoid[Env].empty): Log =
    run(c)(config)(env)._1
    
  def mkErr[F[_]: Applicative](e: Err): F[Err] =
    Applicative[F].pure(e)

  // Utility
  def runLocalW[A](f: Env => Env)(c: WriterEC[A]): WriterEC[A] = {
    val cr: ReaderEC[(Log, A)] = c.run
    val x: ReaderEC[(Log, A)] = cr.local(f)
    val r: WriterEC[(Log, A)] = readerEC2writer(x)
    r.mapBoth { case (_, (w, x)) => (w, x) }
  }

  def readerConfig2check[A](c: ReaderConfig[A]): Check[A] =
    readerEC2check(c.liftT[λ[(F[_], A) => Kleisli[F, Env, A]]])

  def readerEC2writer[A](c: ReaderEC[A]): WriterEC[A] =
    c.liftT[λ[(F[_], A) => WriterT[F, Log, A]]]

  def readerEC2check[A](c: ReaderEC[A]): Check[A] =
    writerEC2check(c.liftT[λ[(F[_], A) => WriterT[F, Log, A]]])

  def writerEC2check[A](c: WriterEC[A]): Check[A] =
    c.liftT[λ[(F[_], A) => EitherT[F, Err, A]]]

}