/* Contextual, version 0.12. Copyright 2016 Jon Pretty, Propensive Ltd.
 *
 * The primary distribution site is: http://co.ntextu.al/
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package contextual

import scala.reflect._, macros._
import scala.annotation.implicitNotFound

import language.experimental.macros
import language.higherKinds
import language.implicitConversions
import language.existentials

/** Represents a compile-time failure in interpolation. */
case class InterpolationError(part: Int, offset: Int, message: String) extends Exception

/** A `Context` describes the nature of the position in an interpolated string where a
  * substitution is made, and determines how values of a particular type should be interpreted
  * in the given position. */
trait Context {
  override def toString: String = getClass.getName.split("\\.").last.dropRight(1)
}

object Embedded {
  implicit def embed[CC <: (Context, Context), V, R, I <: Interpolator](value: V)
      (implicit embedder: Embedder[CC, V, R, I]): Embedded[R, I] =
    new Embedded[R, I] {
      def apply(ctx: Context): R = {
        embedder(ctx).apply(value)
      }
    }
}

abstract class Embedded[R, I <: Interpolator] {
  def apply(ctx: Context): R
}

object Prefix {
  /** Creates a new prefix. This should be applied directly to a named value in an implicit
    * class that wraps a `StringContext` to bind an interpolator object to a prefix of the
    * given name. */
  def apply(interpolator: Interpolator, stringContext: StringContext):
      Prefix[interpolator.Ctx, interpolator.type] =
    new Prefix(interpolator, stringContext.parts)
}

/** A `Prefix` represents the attachment of an `Interpolator` to a `StringContext`, typically
  * using an implicit class. It has only a single method, `apply`, with a signature that's
  * appropriate for fitting the shape of a desugared interpolated string application. */
class Prefix[C <: Context, P <: Interpolator { type Ctx = C }](interpolator: P,
    parts: Seq[String]) {
 
  /** The `apply` method is typically invoked as a result of the desugaring of a StringContext
    * during parsing in Scalac. The method signature takes multiple `Embedded` parameters,
    * which are designed to be created as the result of applying an implicit conversion, which
    * will only succeed with appropriate `Embedding` implicits for embedding that type within
    * the interpolated string.
    *
    * The method is implemented with the main `contextual` macro. */
  def apply(exprs: Embedded[interpolator.Inputs, interpolator.type]*): Any =
    macro Macros.contextual[C, P]
}

/** An `Interpolator` defines the compile-time and runtime behavior when interpreting an
  * interpolated string. */
trait Interpolator extends ContextualParts { interpolator =>
    
  /** The `Contextual` type is a representation of the known compile-time information about an
    * interpolated string. Most importantly, this includes the literal parts of the interpolated
    * string; the constant parts which surround the variables parts that are substituted into
    * it. The `Contextual` type also provides details about these holes, specifically the
    * possible set of contexts in which the substituted value may be interpreted. */
  class Contextual[Parts >: Literal <: Part](val literals: Seq[String], val interspersions: Seq[Parts]) {

    override def toString = Seq("" +: interspersions, literals).transpose.flatten.mkString

    /** The macro context when expanding the `contextual` macro. */
    val context: whitebox.Context = null

    /** The expressions that are substituted into the interpolated string. */
    def expressions: Seq[context.Tree] = Nil
    
    lazy val universe: context.universe.type = context.universe
    
    def interpolatorTerm: Option[context.Symbol] = None

    object Implementation {
      def apply[T: Implementer](v: T): Implementation = new Implementation {
        type Type = T
        def value: Type = v
        def implementer: Implementer[Type] = implicitly[Implementer[Type]]
      }
    }

    def doEvaluation(contexts: Seq[Ctx]): Implementation = new Implementation {
      type Type = context.Tree
      
      def value: context.Tree = {
        import context.universe.{Literal => _, _}

        val literalPartTrees: Seq[context.Tree] = literals.zipWithIndex.map {
          case (lit, idx) => q"_root_.contextual.Literal($idx, $lit)"
        }

        val literalParts: Seq[Literal] = literals.zipWithIndex.map {
          case (lit, idx) => Literal(idx, lit)
        }

        val interpolator = interpolatorTerm.get

        val substitutions = contexts.zip(expressions).zipWithIndex.map {
          case ((ctx, Apply(Apply(_, List(value)), List(embedder))), idx) =>

            /* TODO: Avoid using runtime reflection to get context objects, if we can. */
            val reflectiveContextClass =
              q"_root_.java.lang.Class.forName(${ctx.getClass.getName})"
            
            val reflectiveContext =
              q"""$reflectiveContextClass.getField("MODULE$$").get($reflectiveContextClass)"""

            val castReflectiveContext = q"$reflectiveContext"

            q"$interpolator.Substitution($idx, $embedder($castReflectiveContext).apply($value))"
        }

        q"""$interpolator.evaluate(
          new $interpolator.Contextual[$interpolator.RuntimePart](
            _root_.scala.collection.Seq(..$literals),
            _root_.scala.collection.Seq(..$substitutions)
          )
        )"""
      }
      
      def implementer = Implementer.quasiquotes
    }

    trait Implementation {
      type Type
      def value: Type
      def implementer: Implementer[Type]
      def tree: context.Tree = implementer.tree(value)
    }

    object Implementer {
      implicit val string: Implementer[String] = new Implementer[String] {
        def tree(string: String): context.Tree = {
          import context.universe._
          q"$string"
        }
      }
      
      implicit val quasiquotes: Implementer[context.Tree] = new Implementer[context.Tree] {
        def tree(expr: context.Tree): context.Tree = expr
      }
    }
   
    /** An `Implementer` defines how a particular value should be converted into an AST tree
      * for evaluation at runtime. */
    @implicitNotFound("cannot create an implementation based on the type ${T}")
    trait Implementer[T] { def tree(value: T): context.Tree }

    /** Provides the sequence of `Literal`s and `Hole`s in this interpolated string. */
    def parts: Seq[Parts] = {
      val literalsHead +: literalsTail = literals.zipWithIndex.map { case (lit, idx) =>
        Literal(idx, lit)
      }
     
      literalsHead +: Seq(interspersions, literalsTail).transpose.flatten
    }
  
  }

  /** Returns an `Implementation` representing (in some form) the code that will be executed
    * at runtime when evaluating an interpolated string. Typically, the implementation of this
    * method will do additional checks based on the information known about the interpolated
    * string at compile time, and will report any warnings or errors during compilation. */
  def implement(contextual: Contextual[StaticPart]): contextual.Implementation

  def parse(string: String): Any = string

  class Embedding[I] protected[Interpolator] () {
    def apply[CC <: (Context, Context), R](cases: Transition[CC, I, R]*):
        Embedder[CC, I, R, interpolator.type] = new Embedder(cases)
  }

  def embed[I]: Embedding[I] = new Embedding()
}

/** Factory object for creating `Transitions`. */
object Transition {
  /** Creates a new `Transition` for instances of type `Value`, specifying the `context` in
    * which that type may be substituted, and `after` context. */
  def apply[Before <: Context, After <: Context, Value, Input](context: Before, after: After)
      (fn: Value => Input): Transition[(Before, After), Value, Input] =
    new Transition(context, after, fn)
}

/** A `Transition` specifies for a particular `Context` how a value of type `Value` should be
  * converted into the appropriate `Input` type to an `Interpolator`, and how the application of
  * the value should change the `Context` in the interpolated string. */
class Transition[-CC <: (Context, Context), -Value, +Input](val context: Context,
    val after: Context, val fn: Value => Input)

class Embedder[CC <: (Context, Context), V, R, I <: Interpolator](
    val cases: Seq[Transition[CC, V, R]]) {

  def apply[C2](c: C2)(implicit ev: CC <:< (C2, Context)): V => R =
    cases.find(_.context == c).get.fn
}

trait ContextualParts {

  type Ctx <: Context
  type Inputs  

  sealed trait Part extends Product with Serializable
  
  sealed trait StaticPart extends Part with Product with Serializable { def index: Int }

  sealed trait RuntimePart extends Part with Product with Serializable { def index: Int }

  /** A `Hole` represents all that is known at compile-time about a substitution into an
    * interpolated string. */
  case class Hole(index: Int, input: Map[Ctx, Ctx]) extends StaticPart {
    
    override def toString: String = input.keys.mkString("[", "|", "]")
    
    def apply(ctx: Ctx): Ctx =
      input.get(ctx).getOrElse(abort(
          "values of this type cannot be substituted in this position"))

    /** Aborts compilation, positioning the caret at this hole in the interpolated string,
      *  displaying the error message, `message`. */
    def abort(message: String): Nothing = throw InterpolationError(index, -1, message)
  }

  case class Substitution(index: Int, val value: Inputs) extends RuntimePart {
    def apply(): Inputs = value
    override def toString = value.toString
  }

  /** Represents a fixed, constant part of an interpolated string, known at compile-time. */
  case class Literal(index: Int, string: String) extends StaticPart with RuntimePart {

    override def toString: String = string
    
    /** Aborts compilation, positioning the caret at the `offset` into this literal part of the
      * interpolated string, displaying the error message, `message`. */
    def abort(offset: Int, message: String) =
      throw InterpolationError(index, offset, message)
  }
}

