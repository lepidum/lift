/*
 * Copyright 2009 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package net.liftweb.wizard

import _root_.net.liftweb.http._
import _root_.net.liftweb.common._
import _root_.net.liftweb.util._
import _root_.net.liftweb.mapper._
import Helpers._
import _root_.scala.xml._
import _root_.scala.reflect.Manifest

object WizardRules {
  @volatile var dbConnections: List[ConnectionIdentifier] = List(DefaultConnectionIdentifier)

  private def m[T](implicit man: Manifest[T]): Manifest[T] = man

  private def textInfo(field: SettableValueHolder{type ValueType = String}) = SHtml.text(field.is, field.set _)
private def intInfo(field: SettableValueHolder{type ValueType = Int}) = SHtml.text(field.is.toString,s => Helpers.asInt(s).foreach(field.set _))



}

trait Wizard {
  @volatile private[this] var _screenList: List[Screen] = Nil
  private object ScreenVars extends RequestVar[Map[String, (WizardVar[_], Any)]](Map())
  private object CurrentScreen extends RequestVar[Box[Screen]](calcFirstScreen)
  private object PrevSnapshot extends RequestVar[Box[WizardSnapshot]](Empty)
  private object Referer extends WizardVar[String](S.referer openOr "/")

def toForm = {
  Referer.is // touch to capture the referer
  val nextId = Helpers.nextFuncName
  val prevId = Helpers.nextFuncName
  val cancelId = Helpers.nextFuncName

  val theScreen = currentScreen openOr S.redirectTo(Referer.is)

  val nextButton = theScreen.nextButton % ("onclick" -> ("document.getElementById("+nextId.encJs+").submit()"))
  val prevButton = theScreen.prevButton % ("onclick" -> ("document.getElementById("+prevId.encJs+").submit()"))
  val cancelButton = theScreen.cancelButton % ("onclick" -> ("document.getElementById("+cancelId.encJs+").submit()"))


  val url = S.uri
  val snapshot = createSnapshot

  def doNext() {
    this.nextScreen
    if (currentScreen.isEmpty) S.redirectTo(Referer.is)
  }

  <form id={nextId} action={url} method="post">{
      SHtml.hidden(() => snapshot.restore())
    }
    <table>
      {
        theScreen.screenFields.map(f =>
        <tr><td>{f.titleAsHtml}</td><td>{f.toForm}</td></tr>)
      }
    </table>
    {
    S.formGroup(4)(SHtml.hidden(() => doNext()))

    }
      </form> ++
   <form id={prevId} action={url} method="post">{
       SHtml.hidden(() => {snapshot.restore(); this.prevScreen})
     }</form> ++
   <form id={cancelId} action={url} method="post">{
       SHtml.hidden(() => {snapshot.restore(); S.redirectTo(Referer.is)})
     }</form> ++ prevButton ++ cancelButton ++ nextButton
}

  class WizardSnapshot(private[wizard] val screenVars: Map[String, (WizardVar[_], Any)],
                       val currentScreen: Box[Screen],
                       private[wizard] val snapshot: Box[WizardSnapshot]) {
    def restore() {
      ScreenVars.set(screenVars)
      CurrentScreen.set(currentScreen)
      PrevSnapshot.set(snapshot)
    }
  }
  
  private def _register(screen: Screen) {
    _screenList = _screenList ::: List(screen)
  }

  def dbConnections: List[ConnectionIdentifier] = WizardRules.dbConnections

  /**
   * The ordered list of Screens
   */
  def screens: List[Screen] = _screenList

  /**
   * Given the current screen, what's the next screen?
   */
  def calcScreenAfter(which: Screen): Box[Screen] = 
    screens.dropWhile(_ ne which).drop(1).firstOption
  

  /**
   * What's the first screen in this wizard
   */
  def calcFirstScreen: Box[Screen] = screens.firstOption

  def nextButton: Elem = <button>{S.??("Next")}</button>

  def prevButton: Elem = <button>{S.??("Previous")}</button>

  def cancelButton: Elem = <button>{S.??("Cancel")}</button>

  def finishButton: Elem = <button>{S.??("Finish")}</button>

  def currentScreen: Box[Screen] = CurrentScreen.is

  def createSnapshot = new WizardSnapshot(ScreenVars.is, CurrentScreen.is, PrevSnapshot.is)

  /**
   * This method will be called within a transactional block when the last screen is completed
   */
  protected def finish(): Unit

  def nextScreen {
    for {
      screen <- CurrentScreen.is
    } {
      screen.validate match {
        case Nil =>
          val snapshot = createSnapshot
          PrevSnapshot.set(Full(snapshot))
          val nextScreen = screen.nextScreen
          CurrentScreen.set(screen.nextScreen)

          nextScreen match {
            case Empty =>
              def useAndFinish(in: List[ConnectionIdentifier]) {
                in match {
                  case Nil => finish()

                  case x :: xs => DB.use(x) {
                      conn =>
                      useAndFinish(xs)
                    }
                }
              }
              useAndFinish(dbConnections)

            case _ =>
          }
        case xs => S.error(xs)
      }
    }
  }

  def prevScreen {
    for {
      snapshot <- PrevSnapshot.is
    } {
      snapshot.restore()
    }
  }

  /**
   * By default, are all the fields on all the screen in this wizardn on the confirm screen?
   */
  def onConfirm_? = true

  /**
   * Define a screen within this wizard
   */
  trait Screen {
    override def toString = screenName

    @volatile private[this] var _fieldList: List[Field] = Nil
    private def _register(field: Field) {
      _fieldList = _fieldList ::: List(field)
    }

    /**
     * A list of fields in this screen
     */
    def screenFields = _fieldList

    val myScreenNum = screens.length

    /**
     * The name of the screen.  Override this to change the screen name
     */
    def screenName: String = "Screen "+(myScreenNum + 1)

    def screenNameAsHtml: NodeSeq = Text(screenName)

    def screenTitle: NodeSeq = screenNameAsHtml

    def screenTopText: Box[String] = Empty

    def screenTopTextAsHtml: Box[NodeSeq] = screenTopText.map(Text.apply)

    def nextButton: Elem = Wizard.this.nextButton

    def prevButton: Elem = Wizard.this.prevButton

    def cancelButton: Elem = Wizard.this.cancelButton

    def finishButton: Elem = Wizard.this.finishButton

    def nextScreen: Box[Screen] = calcScreenAfter(this)

    implicit def boxOfScreen(in: Screen): Box[Screen] = Box !! in
    /**
     * By default, are all the fields on this screen on the confirm screen?
     */
    def onConfirm_? = Wizard.this.onConfirm_?


    def validate: List[FieldError] = screenFields.flatMap(_.validate)

    /**
     * Is this screen a confirm screen?
     */
    def confirmScreen_? = false
  
    /**
     * Define a field within the screen
     */
    trait Field extends FieldIdentifier with SettableValueHolder {
      type ValueType
      Screen.this._register(this)

      object currentValue extends WizardVar[ValueType](default) {
        override protected def __nameSalt = randomString(20)
      }

      def default: ValueType

      def is = currentValue.is

def get = is

      def set(v: ValueType) = currentValue.set(v)

      implicit def manifest: Manifest[ValueType]

      protected def buildIt[T](implicit man: Manifest[T]): Manifest[T] = man

      def title: String

      def titleAsHtml: NodeSeq = Text(title)

      def help: Box[String] = Empty

      def helpAsHtml: Box[NodeSeq] = help.map(Text.apply)

      /**
       * Is the field editable
       */
      def editable_? = true

def toForm: NodeSeq

      /**
       * Is this field on the confirm screen
       */
      def onConfirm_? = Screen.this.onConfirm_?

      def validate: List[FieldError] = validation.flatMap(_.apply(is))

      def validation: List[ValueType => List[FieldError]] = Nil

      override lazy val uniqueFieldId: Box[String] = Full(Helpers.hash(this.getClass.getName))
    }

    Wizard.this._register(this)
  }

  /**
   * Keep request-local information around without the nastiness of naming session variables
   * or the type-unsafety of casting the results.
   * RequestVars share their value through the scope of the current HTTP
   * request.  They have no value at the beginning of request servicing
   * and their value is discarded at the end of request processing.  They
   * are helpful to share values across many snippets.
   *
   * @param dflt - the default value of the session variable
   */
  abstract class WizardVar[T](dflt: => T) extends NonCleanAnyVar[T](dflt) {
    override protected def findFunc(name: String): Box[T] = WizardVarHandler.get(name)

    override protected def setFunc(name: String, value: T): Unit = WizardVarHandler.set(name, this, value)

    override protected def clearFunc(name: String): Unit = WizardVarHandler.clear(name)

    override protected def wasInitialized(name: String): Boolean = {
      val bn = name + "_inited_?"
      val old: Boolean = WizardVarHandler.get(bn) openOr false
      WizardVarHandler.set(bn, this, true)
      old
    }

    override protected def testWasSet(name: String): Boolean = {
      val bn = name+"_inited_?"
      WizardVarHandler.get(name).isDefined || (WizardVarHandler.get(bn) openOr false)
    }
  }
  

  private[wizard] object WizardVarHandler /* extends LoanWrapper */ {
    //def vals = ScreenVars.is


    def get[T](name: String): Box[T] =
    ScreenVars.is.get(name).map(_._2.asInstanceOf[T])


    def set[T](name: String, from: WizardVar[_], value: T): Unit =
    ScreenVars.set(ScreenVars.is + (name -> (from, value)))

    def clear(name: String): Unit =
    ScreenVars.set(ScreenVars.is - name)
  }
}

trait IntField extends FieldIdentifier {
  self: Wizard#Screen#Field =>
  type ValueType = Int
  def default = 0
  lazy val manifest = buildIt[Int]

  def minVal(len: Int, msg: => String): Int => List[FieldError] = s =>
  if (s < len) List(FieldError(this, Text(msg))) else Nil

  def maxVal(len: Int, msg: => String): Int => List[FieldError] = s =>
  if (s > len) List(FieldError(this, Text(msg))) else Nil

  def toForm: NodeSeq = SHtml.text(this.is.toString, s => Helpers.asInt(s).foreach(this.set _))
}

trait StringField extends FieldIdentifier {
  self: Wizard#Screen#Field =>
  type ValueType = String
  def default = ""
  lazy val manifest = buildIt[String]

  def minLen(len: Int, msg: => String): String => List[FieldError] = s => 
  if (s.length < len) List(FieldError(this, Text(msg))) else Nil

  def maxLen(len: Int, msg: => String): String => List[FieldError] = s =>
  if (s.length > len) List(FieldError(this, Text(msg))) else Nil

  def toForm: NodeSeq = SHtml.text(this.is, this.set _)
}

/*
 object Wizard {
 trait Field extends SettableValueHolder {
 def validate: List[FieldError]

 /**
  * Should this field appear on the confirmation page
  */
 def confirmPage_? = true

 def asBindParam: BindParam

 /**
  * The localized display name of this field
  */
 def displayName: NodeSeq = Text(bindName)

 def bindName: String

 def toForm: Box[NodeSeq]
 }

 trait LocalField extends Field {
 object currentValue extends WizardVar[ValueType](default) {
 override protected def __nameSalt = randomString(20)
 }

 def default: ValueType

 def is = currentValue.is

 def set(v: ValueType) = currentValue.set(v)

 }

 object Field {
 }

 private object NextScreen extends RequestVar[Box[Screen]](Empty) {
 def unapply(x: Any): Option[Screen] = this.is
 }

 private object ScreenVars extends RequestVar[Map[String, (WizardVar[_], Any)]](Map())

 trait Screen {
 def templateName: Box[String] = Empty

 def locale: Locale = S.locale

 def template: NodeSeq = templateName.flatMap(s => TemplateFinder.findAnyTemplate(s.roboSplit("/"), locale)) openOr NodeSeq.Empty

 def nextScreen: Box[Screen] = Empty

 def finished: () => Unit = () => ()

 def fields: List[Field] = Nil

 def validate: List[FieldError] = fields.flatMap(_.validate)

 def howManyMore_? : Box[Int] = Empty

 def lastScreen_? = true

 def buildContinuation: NodeSeq = {
 val currentScreenVars = ScreenVars.is
 SHtml.hidden(() => ScreenVars.set(currentScreenVars))
 }

 def screenContent(in: NodeSeq) = {


 <form mathod="post" action={S.uri}>{buildContinuation}{bind(bindName, template, fields.map(_.asBindParam): _*)}</form> % formAttrs
 }

 def formAttrs: MetaData = Null

 def bindName = "wizard"

 def &>(other: Screen): Screen = {
 val self = this
 new ProxyScreen {
 def proxyTo = self

 override def nextScreen: Box[Screen] = Full(other)
 }
 }

 /*
  def &>[Me <: Screen](other: PartialFunction[Me, Screen]): Screen = {
  val self = this
  new ProxyScreen {
  def proxyTo = self
  override def nextScreen: Box[Screen] = if ()
  }
  }
  */
 }

 trait ProxyScreen extends Screen {
 def proxyTo: Screen

 override def dispatch = proxyTo.dispatch

 override def templateName: Box[String] = proxyTo.templateName

 override def locale: Locale = proxyTo.locale

 override def template: NodeSeq = proxyTo.template

 override def nextScreen: Box[Screen] = proxyTo.nextScreen

 override def finished: () => Unit = proxyTo.finished

 override def fields: List[Field] = proxyTo.fields

 override def validate: List[FieldError] = proxyTo.validate

 override def howManyMore_? : Box[Int] = proxyTo.howManyMore_?

 override def lastScreen_? = proxyTo.lastScreen_?

 override def buildContinuation: NodeSeq = proxyTo.buildContinuation

 override def screenContent(in: NodeSeq) = proxyTo.screenContent(in)


 override def formAttrs: MetaData = proxyTo.formAttrs

 override def bindName = proxyTo.bindName
 }


 object Screen {
 }

 /**
  * Keep request-local information around without the nastiness of naming session variables
  * or the type-unsafety of casting the results.
  * RequestVars share their value through the scope of the current HTTP
  * request.  They have no value at the beginning of request servicing
  * and their value is discarded at the end of request processing.  They
  * are helpful to share values across many snippets.
  *
  * @param dflt - the default value of the session variable
  */
 abstract class WizardVar[T](dflt: => T) extends NonCleanAnyVar[T](dflt) {
 override protected def findFunc(name: String): Box[T] = WizardVarHandler.get(name)

 override protected def setFunc(name: String, value: T): Unit = WizardVarHandler.set(name, this, value)

 override protected def clearFunc(name: String): Unit = WizardVarHandler.clear(name)

 override protected def wasInitialized(name: String): Boolean = {
 val bn = name + "_inited_?"
 val old: Boolean = WizardVarHandler.get(bn) openOr false
 WizardVarHandler.set(bn, this, true)
 old
 }
 }

 private object WizardVarHandler /* extends LoanWrapper */ {
 //def vals = ScreenVars.is


 def get[T](name: String): Box[T] =
 ScreenVars.is.get(name).map(_._2.asInstanceOf[T])


 def set[T](name: String, from: WizardVar[_], value: T): Unit =
 ScreenVars.set(ScreenVars.is + (name -> (from, value)))

 def clear(name: String): Unit =
 ScreenVars.set(ScreenVars.is - name)
 }


 /*
  case class WizardPage[T] (
  val setup: (block: T => T),
  def next: Option[WizardPage[T],
  ) {
  def next_> (block: T => T) =
  new WizardPage(setup, Wizard(block))
  def choose...?
  }
  object Wizard { def apply(block: T => T) = WizardPage(block, None) }

  Wizard { t =>
  [bind snippets for first page]
  } next_> { t =>
  [second page]
  } choose {
  case t if t.something =>
  next_> { t =>
  [third if something]
  } next_> {
  [fourth, if something]
  } next_> {
  [fifth & last, if something]
  }
  case t =>
  next_> {
  [third and final if not something]
  }
  */
 }

 */
