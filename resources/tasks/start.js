const bs = require('browser-sync').create()
const generator = require('@neo4j/developer-site-generator')

const antoraArgs = ['--playbook', 'local-antora-playbook.yml']

;(async () => {
  await generator(antoraArgs, process.env)
  bs.init({
    startPath: '/graphacademy/',
    server: './public'
  })
})()
