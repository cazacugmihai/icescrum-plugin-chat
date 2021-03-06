/*
 * Copyright (c) 2011 BE ISI iSPlugins Université Paul Sabatier.
 *
 * This file is part of iceScrum.
 *
 * Chat plugin is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * Chat plugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Chat plugin.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:	Claude AUBRY (claude.aubry@gmail.com)
 *		Vincent Barrier (vbarrier@kagilum.com)
 *		Marc-Antoine BEAUVAIS (marcantoine.beauvais@gmail.com)
 *		Jihane KHALIL (khaliljihane@gmail.com)
 *		Paul LABONNE (paul.labonne@gmail.com)
 *		Nicolas NOULLET (nicolas.noullet@gmail.com)
 *		Bertrand PAGES (pages.bertrand@gmail.com)
 *
 *
 */

import grails.converters.JSON
import org.icescrum.core.domain.User
import org.icescrum.plugins.chat.ChatConnection
import org.icescrum.core.domain.Story
import org.icescrum.core.domain.User
import grails.plugins.springsecurity.Secured
import org.icescrum.core.support.ApplicationSupport
import org.icescrum.core.utils.BundleUtils
import org.icescrum.core.domain.Task

class ChatController {

 static final pluginName = 'icescrum-plugin-chat'

  def springSecurityService
  def securityService
  def chatService
  def grailsApplication

  def index = {

    def user = springSecurityService.currentUser
    if (!user) {
        render(status:200)
        return
    }

    def chatPreferences = chatService.getChatPreferences(user)

    if (!ApplicationSupport.booleanValue(grailsApplication.config.icescrum.chat.enabled) || chatPreferences.disabled){
        render(status:200)
        return
    }

    def statusKeys = []
    def statusLabels =[]
    def statusIcons = []

    def statusType = ['online','dnd','away']
    for(status in statusType){
      statusKeys.add(status)
      statusLabels.add(g.message(code:'is.chat.status.'+status))
    }

    statusKeys.add('disc')
    statusLabels.add(g.message(code:'is.chat.status.disconnected'))

    def teamList = []
    def userList = []
    user.teams?.each{t->
      def jsonTeam = []
      t.members?.each{u->
        if(u.id != user.id && !userList.contains(u.id)) {
          def chatPref = chatService.getChatPreferences(u)
          if (chatPref.username){
            jsonTeam.add([id:u.id, jid:chatPref.username, lastname:u.lastName, firstname:u.firstName])
          }
          userList.add(u.id)
        }
      }
      def teamName = t.products.size() > 1 ? t.name : t.products?.toList()[0]?.name ?: t.name
      teamList.add(teamname:teamName, teamid:t.id, users:jsonTeam)
    }
    teamList = teamList as JSON

    render(template:'widget/widgetView',plugin:pluginName, model:[
            teamList:teamList,
            user:user,
            statusKeys:statusKeys,
            statusLabels:statusLabels,
            statusIcons:statusIcons,
            needConfiguration:chatPreferences.needConfiguration()])
  }


  def connection = {
    def user = springSecurityService.currentUser
    def chatConnection = new ChatConnection()
    def pref = chatService.getChatPreferences(user);
    if(chatConnection.connect(pref, grailsApplication.config.icescrum.chat.bosh, grailsApplication.config.icescrum.chat.resource,  params.boolean('video')?:false)){
      pref.show = pref.show != 'disc' ? pref.show : 'online'
      pref.save()
      render(status:200,text:[sid:chatConnection.sid,rid:chatConnection.rid,jid:chatConnection.jid] as JSON, contentType: 'application/json')
    }else{
      render(status:400)
    }
  }

  def tooltip = {
    withUser{ User user ->
        def tasks =  []
        if(params.product) {
          tasks = Task.getAllInProduct(params.product.toLong())
          tasks = tasks.findAll { Task task ->
              task.state == Task.STATE_BUSY && task.responsible == user
          }
        }
        render(status:200,contentType: 'text/html', template:'tooltipChat',plugin:pluginName,model:[escapedJid:params.escapedJid,m:user,tasks:tasks,nbtasks:tasks.size() > 1 ? 's' : 0])
        return
    }
  }

  def status = {
    def user = User.get(springSecurityService.principal.id)
    try {
      if(user){
        def chatPreferences = chatService.getChatPreferences(user)
        chatPreferences.show = params.show
        chatPreferences.presence = params.presence
        chatService.saveChatPreferences(chatPreferences)
        render(status:200)
      }else{
        render(status:400)
      }
    }catch(Exception e){
      render(status:400)
    }
  }

    def jid = {
        def user = User.get(springSecurityService.principal.id)
        try{
         if (user){
            def chatPreferences = chatService.getChatPreferences(user)
            chatPreferences.username = params.jid
            chatService.saveChatPreferences(chatPreferences)
            render(status:200)
         }else{
           render(status:400)
         }
        }catch(Exception e){
          render(status:400)
        }
    }

  def form = {
      if (grailsApplication.config.icescrum.chat.enabled){
          def chatPref = chatService.getChatPreferences(params.user)
          def oauthKeys = ["custom"]
          def oauthValues = ["manual"]
          if (grailsApplication.config.icescrum.chat.facebook?.apiKey){
              oauthKeys << "Facebook Chat"
              oauthValues << "facebook"
          }
          if (grailsApplication.config.icescrum.chat.gtalk?.apiKey){
              oauthKeys << "Google Talk"
              oauthValues << "gtalk"
          }
          if (grailsApplication.config.icescrum.chat.live?.apiKey){
              oauthKeys << "Microsoft Live"
              oauthValues << "live"
          }
          render(template:'dialogs/profileTabContent', plugin:pluginName, model:[chatPreferences:chatPref, oauthKeys:oauthKeys, oauthValues:oauthValues])
      }else{
          render(status:200,text:'')
      }
  }

    def updatePreferences = { params, context ->
      if(params.chatPreferences instanceof Map){
          def chatPref = chatService.getChatPreferences(context.user)
          chatPref.disabled = params.chatPreferencesEnabled != '1'
          chatPref.hideOffline = params.chatPreferencesHideOffline == '1'
          try {
            if (params.method == 'manual'){
                chatPref.password = params.chatPreferences.password
                chatPref.username = params.chatPreferences.username
                chatPref.oauth = null
            }else{
                chatPref.password = null
                chatPref.username = null
                chatPref.oauth = params.method
            }
            chatService.saveChatPreferences(chatPref)
          } catch (RuntimeException re) {
              render(status: 400, contentType: 'application/json', text:[notice: [text: renderErrors(bean:chatPref)]] as JSON)
              return
          }
      }
  }

  def message = {
      def productid = params.long('product')
      def stories = []
      params.list('uid').each {
          def story = Story.getInProductByUid(productid, Integer.parseInt(it)).list()
          if(story) {
              stories << (Story) story
          }
      }
      def urls = []
      stories.each{
            if (!it.backlog.preferences.hidden || securityService.inProduct(it.backlog.id, springSecurityService.authentication)){
                urls << [uid:'story-'+it.uid,
                       external:createLink(absolute: true, mapping: "shortURL", params: [product: it.backlog.pkey], id: it.uid),
                       internal:is.createScrumLink(product:it.backlog.id,controller:'story',id:it.id),
                       name:it.name,
                       estimation:it.effort?:'?',
                       state: g.message(code:BundleUtils.storyStates[it.state])]
            }
      }
      render(status:200,text:urls as JSON, contentType: 'application/json')
  }

    def peerMessage = {
        params.remove('controller')
        params.remove('action')
        String to = params.remove('to')
        def username = ChatPreferences.findByUsername(to)?.user?.username
        if (username){
            broadcastToSingleUser(user:username, function:'peerMessageReceived', message:[class:'Chat',message:params])
        }
        render(status:200,text:'')
    }

  def oauth = {
      render(status:200,text:'')
  }
}