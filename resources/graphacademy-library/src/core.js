import WebAuth from 'auth0-js';
import Quiz from './quiz.js';
import Enrollment from './enrollment.js';
import Certificate from './certificate.js';

function validateOptions(options, requiredOptions) {
  const opts = {...options}
  const hasRequiredOptions = requiredOptions.every(item => opts[item])
  if (!hasRequiredOptions) {
    throw new Error(`required option missing - one of ${requiredOptions.join(', ')}`)
  }
  return opts
}

export default class GraphAcademyCore {
  constructor(options = {}) {
    const requiredOptions = ['trainingClassName', 'enrollmentUrl', 'authResult']
    const defaultOptions = {
      stage: 'prod',
    }
    this.options = {...defaultOptions, ...validateOptions(options, requiredOptions)}
    this.webAuth = new WebAuth({
      clientID: 'hoNo6B00ckfAoFVzPTqzgBIJHFHDnHYu',
      domain: 'login.neo4j.com',
      redirectUri: `${window.location.origin}/accounts/login`,
      audience: 'neo4j://accountinfo/',
      scope: 'read:account-info openid email profile user_metadata',
      responseType: 'token id_token'
    })
    const {stage, trainingClassName} = this.options
    this.certificate = new Certificate(trainingClassName, stage)
    this.enrollment = new Enrollment(trainingClassName, stage)
    this.quiz = new Quiz(trainingClassName, stage)

  }

  async login() {
    const {webAuth} = this
    // we are using an explicit Promise because the value is returned in a callback.
    // the async function will be resolved when the callback is called.
    return new Promise((resolve, _) => {
      webAuth.checkSession({}, (err, result) => {
        if (result) this.authResult = result;
        return resolve([err, result]);
      })
    })
  }

  async enrollStudentInClass(firstName, lastName) {
    this.validateRequest();
    return this.enrollment.enrollStudentInClass(firstName, lastName, this.authResult.accessToken)
  }

  async getEnrollmentForClass() {
    this.validateRequest();
    return this.enrollment.getEnrollmentForClass(this.authResult.accessToken)
  }

  async getQuizStatus() {
    this.validateRequest();
    return this.quiz.getQuizStatus(this.authResult.accessToken)
  }

  async getClassCertificate() {
    this.validateRequest();
    return this.certificate.getClassCertificate(this.authResult.accessToken)
  }

  async postQuizStatus(passed, failed) {
    this.validateRequest();
    return this.quiz.postQuizStatus(passed, failed, this.authResult.accessToken)
  }

  validateRequest() {
    if (!this.authResult || !this.authResult.accessToken) {
      throw new Error(`accessToken not available. Cannot make a request to API`);
    }
  }

  logout() {
    const {options} = this
    const logoutOptions = {}
    if (options.logoutOptions && options.logoutOptions.shouldRedirect) {
      logoutOptions.redirectTo = options.redirectOnLogout
    }
    this.webAuth.logout(logoutOptions)
  }

  redirectToLogin() {
    const {options} = this
    return window.location.href = options.loginRedirectUrl
  }
}
