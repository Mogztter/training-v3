/* global $, jwt_decode */
document.addEventListener('DOMContentLoaded', function () {

  var gradeQuizActionElement = $('[data-action="grade-quiz"]')
  var quizElement = $('.quiz').first()
  var siteUrl = window.location.href

  var trainingName = window.trainingClassName
  var trainingModules = window.trainingClassModules
  var trainingPartName = window.trainingPartName

  var quizStatusLocalStorageKey = 'com.neo4j.graphacademy.' + trainingName + '.quizStatus'

  var backendBaseUrl = window.trainingBackendBaseUrl
  var enrollmentUrl = window.trainingEnrollmentUrl

  function arrayDiff(a, b) {
    return a.filter(function (i) {
      return b.indexOf(i) < 0
    })
  }

  function getQuizStatus(accessToken) {
    return $.ajax({
      type: 'GET',
      url: backendBaseUrl + '/getQuizStatus?className=' + trainingName,
      contentType: 'application/json',
      dataType: 'json',
      async: true,
      headers: {
        'Authorization': accessToken
      }
    })
  }

  function setQuizStatus(passed, failed, accessToken) {
    var data = {
      'className': window.trainingClassName,
      'passed': passed,
      'failed': failed
    }
    return $.ajax({
      type: 'POST',
      url: backendBaseUrl + '/setQuizStatus',
      contentType: 'application/json',
      dataType: 'json',
      async: true,
      data: JSON.stringify(data),
      headers: {
        'Authorization': accessToken
      }
    })
  }

  function getClassCertificate(accessToken) {
    return $.ajax({
      type: 'POST',
      url: backendBaseUrl + '/genClassCertificate',
      contentType: 'application/json',
      dataType: 'json',
      async: true,
      data: JSON.stringify({'className': trainingName}),
      headers: {
        'Authorization': accessToken
      }
    })
  }

  function getEnrollmentForClass(accessToken) {
    return $.ajax({
      type: 'GET',
      url: backendBaseUrl + '/getClassEnrollment?className=' + trainingName,
      async: true,
      headers: {
        'Authorization': accessToken
      }
    })
  }

  function updatePageQuiz(gradeQuizActionStatus) {
    // && is (somehow) encoded as &#38;
    if (currentQuizStatus) {
      if (currentQuizStatus.passed) {
        if (currentQuizStatus.passed.indexOf(trainingPartName) !== -1) {
          if (gradeQuizActionStatus === 'disabled') {
            // disable the button to avoid disruption in the UI
            gradeQuizActionElement.prop('disabled', true);
          } else {
            gradeQuizActionElement.hide()
          }
          quizElement.find('.required-answer').each(function () {
            $(this).prev(':checkbox').prop('checked', true)
          })
          quizElement.find('.false-answer').each(function () {
            $(this).prev(':checkbox').prop('checked', false)
          })
        }
      }
    }
  }

  function updateProgressIndicators(quizStatus) {
    document.querySelectorAll('[data-progress-indicator]').forEach(function (element) {
      var slug = element.dataset.slug
      // reset
      var classList = element.classList
      while (classList.length > 0) {
        classList.remove(classList.item(0))
      }
      var className
      if (quizStatus.failed.indexOf(slug) !== -1) {
        className = 'failed'
      } else if (quizStatus.passed.indexOf(slug) !== -1) {
        className = 'passed'
      } else if (quizStatus.untried && quizStatus.untried.indexOf(slug) !== -1) {
        className = 'untried'
      } else {
        className = 'none'
      }
      element.classList.add(className)
    })
  }

  function gradeQuiz(slug) {
    if (currentQuizStatus && currentQuizStatus.passed && currentQuizStatus.passed.indexOf(slug) !== -1) {
      // already passed!
      return true
    }
    var quizSuccess = true
    // todo: we should use data attribute on the checkbox instead of span elements
    // todo: we should not define colors but class
    quizElement.find('h3').css('color', '#525865')
    quizElement.find('.required-answer').each(function () {
      if (!$(this).prev(':checkbox').prop('checked')) {
        $(this).closest('.ulist').siblings('h3').css('color', '#F44336')
        quizSuccess = false
      }
    })
    quizElement.find('.false-answer').each(function () {
      if ($(this).prev(':checkbox').prop('checked')) {
        $(this).closest('.ulist').siblings('h3').css('color', '#F44336')
        quizSuccess = false
      }
    })
    if (quizSuccess && currentQuizStatus && currentQuizStatus.passed) {
      currentQuizStatus.passed.push(slug)
    }
    return quizSuccess
  }

  var logout = function () {
    // todo: use a temporary URL during the Auth0 migration (notice the "login-b" instead of "login")
    //window.location = 'http://neo4j.com/accounts/login/?targetUrl=' + encodeURI(siteUrl)
    window.location = 'http://neo4j.com/accounts/login-b/?targetUrl=' + encodeURI(siteUrl)
  }

  // events

  gradeQuizActionElement.click(function (event) {
    event.preventDefault()

    var quizResultElement = $('#quiz-result')
    if (quizResultElement) {
      quizResultElement.remove()
    }

    var target = event.target
    var quizSuccess = gradeQuiz(target.dataset.slug)
    if (quizSuccess) {
      $(target).before('<div id="quiz-result">' +
        '<p class="paragraph">' +
        '<span style="color: #63b345">All good!</span> you can advance to next section.' +
        '</p>' +
        '</div>'
      )
    } else {
      $(target).before('<div id="quiz-result">' +
        '<p class="paragraph">' +
        '<span style="color: #F44336">Please correct errors</span> in quiz responses above to continue. Questions with incorrect responses are highlighted in <span style="color: #F44336">red</span>.' +
        '</p>' +
        '</div>'
      )
    }

    // update indicators
    updateProgressIndicators(currentQuizStatus)
    setQuizStatus(currentQuizStatus['passed'], currentQuizStatus['failed'], accessToken)
      .then(() => {
        window.localStorage.setItem(quizStatusLocalStorageKey, JSON.stringify(currentQuizStatus))
        updateProgressIndicators(currentQuizStatus)
        updatePageQuiz('disabled')
      })
      .catch(function (error) {
        // question: what should we do? display an error message to the user?
        console.error('Unable to update quiz status', error)
      })
  })

  // initial state
  var currentQuizStatus
  var quizStatus = window.localStorage.getItem(quizStatusLocalStorageKey)
  if (quizStatus) {
    try {
      currentQuizStatus = JSON.parse(quizStatus)
    } catch (e) {
      // unable to parse the value of the quiz status from the localStorage, resetting...
      console.warn("Unable to parse the value of the quiz status from the localStorage, resetting...")
      window.localStorage.removeItem(quizStatusLocalStorageKey)
      currentQuizStatus = {
        failed: [],
        passed: [],
        untried: trainingModules
      }
    }
  } else {
    currentQuizStatus = {
      failed: [],
      passed: [],
      untried: trainingModules
    }
  }
  updateProgressIndicators(currentQuizStatus)
  updatePageQuiz()

  if (typeof trainingPartName !== 'undefined') {
    var lock = new Auth0Lock('hoNo6B00ckfAoFVzPTqzgBIJHFHDnHYu', 'login.neo4j.com', {
        configurationBaseUrl: 'https://cdn.auth0.com',
        allowedConnections: ['google-oauth2', 'linkedin', 'twitter', 'Username-Password-Authentication'],
        additionalSignUpFields: [
          {
            name: 'first_name',
            placeholder: 'First Name'
          },
          {
            name: 'last_name',
            placeholder: 'Last Name'
          }
        ],
        closable: false,
        languageDictionary: {
          signUpTerms: "I agree to the <a href='https://neo4j.com/terms/online-trial-agreement/' style='text-decoration: underline' target='_blank'>terms of service</a> of Neo4j."
        },
        mustAcceptTerms: true,
        auth: {
          redirect: true,
          redirectUrl: 'https://neo4j.com/accounts/login',
          responseType: 'token id_token',
          audience: 'neo4j://accountinfo/',
          params: {
            scope: 'read:account-info write:account-info openid email profile user_metadata'
          }
        }
      }
    )
    var accessToken
    lock.checkSession({}, function (err, authResult) {
      if (err) {
        console.error('User is not authenticated', err)
        logout()
      } else if (authResult && authResult.accessToken) {
        // we're authenticated!
        accessToken = authResult.accessToken
        // get the enrollment status
        getEnrollmentForClass(accessToken)
          .then(function (response) {
            if (response) {
              if (response.enrolled === false) {
                // you should be enrolled, redirect to the enrollment page!
                window.location = enrollmentUrl
              }
            }
          })
          .catch(function (error) {
            console.error('Unable to get enrollment', error)
          })
        // if the quiz status is not available from the localStorage, get the current quiz status from the server
        var quizStatus = window.localStorage.getItem(quizStatusLocalStorageKey)
        if (quizStatus === null) {
          getQuizStatus(accessToken)
            .then(function (response) {
              var quizStatus = response['quizStatus']
              if (quizStatus) {
                var failed = quizStatus['failed']
                var passed = quizStatus['passed']
                var untried = arrayDiff(arrayDiff(trainingModules, failed), passed)
                currentQuizStatus = {
                  failed: failed,
                  passed: passed,
                  untried: untried
                }
                window.localStorage.setItem(quizStatusLocalStorageKey, JSON.stringify(currentQuizStatus))
                updateProgressIndicators(currentQuizStatus)
                updatePageQuiz()
              } else {
                console.warn('Unable to update the current quiz status, response from the server is empty', response)
              }
            })
            .catch(function (error) {
              console.error('Unable to get quiz status', error)
            })
        }
        var certificateResultElement = document.querySelector('[data-certificate-result]')
        if (certificateResultElement) {
          // get the certificate
          getClassCertificate(accessToken)
            .then(function (response) {
              if ('url' in response) {
                certificateResultElement.innerHTML = '<p class="paragraph"><a href="' + response['url'] + '">Download Certificate</a></p>'
              } else {
                certificateResultElement.innerHTML = '<p class="paragraph">Certificate not available yet. Did you complete the quizzes at the end of each section?</p>'
              }
            })
            .catch(function (error) {
              console.error('Unable to get certificate', error)
            })
        }
        var userInfo = authResult.idTokenPayload;
        if (window.intercomSettings && window.intercomSettings.app_id && userInfo) {
          try {
            Intercom('update', {
              app_id: window.intercomSettings.app_id,
              name: userInfo.name,
              email: userInfo.email,
              user_id: userInfo.sub,
              hide_default_launcher: true
            })
          } catch (err) {
            console.error('Unable to call Intercom with user info', err)
          }
        }
        Intercom('trackEvent', 'course-' + trainingName + '-' + trainingPartName)
      } else {
        console.warn('Unable to get the access token from the authentication result', authResult)
      }
    })
  }
})
