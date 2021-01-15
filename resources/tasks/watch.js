const chokidar = require('chokidar')
const bs = require('browser-sync').create()
const generator = require('@neo4j/developer-site-generator')
const Lock = require('./extra/lock.js')
const processorLock = new Lock()

const antoraArgs = ['--playbook', 'local-antora-playbook.yml']

let watchPaths
if (process.env && process.env.TRAINING_MODULE) {
  watchPaths = [
    `modules/${process.env.TRAINING_MODULE}/modules/ROOT/pages/**.adoc`,
    'modules/home/modules/ROOT/pages/**.adoc',
  ]
} else {
  watchPaths = [
    'home',
    '4.0-intro-neo4j',
    'graph-data-modeling',
    '4.0-implementing-graph-data-models',
    '4.0-neo4j-admin',
    '4.0-cypher-query-tuning',
    '4.0-intro-graph-algos',
    'online-training-v2',
    'neo4j-administration',
    'gds-applied-algos',
    'datascience',
    'applied-algos'
  ].map((moduleName) => `modules/${moduleName}/modules/ROOT/pages/**.adoc`)
}

const watcher = chokidar.watch(watchPaths, {
  ignored: [
    /(^|[\/\\])\../, // ignore dotfiles
  ],
  persistent: true,
  // avoid issues with Intellij IDEA temporary files (used to automatically save changes)
  // [Error: ENOENT: no such file or directory, lstat '/path/to/training-v3/modules/datascience/modules/ROOT/pages/enrollment.adoc~' in /path/to/training-v3 (ref: antora-local-dev <worktree> | path: modules/datascience)] {
  //   errno: -2,
  //   code: 'ENOENT',
  //   syscall: 'lstat',
  //   path: '/path/to/training-v3/modules/datascience/modules/ROOT/pages/enrollment.adoc~'
  // }
  awaitWriteFinish: {
    stabilityThreshold: 100,
    pollInterval: 50
  }
})

async function generate (_) {
  try {
    const hasQueuedEvents = await processorLock.acquire()
    if (!hasQueuedEvents) {
      await generator(antoraArgs, process.env)
      bs.reload()
    }
  } catch (err) {
    console.error(err)
  } finally {
    processorLock.release()
  }
}

watcher.on('change', async e => await generate(e))
watcher.on('unlink', async e => await generate(e))

;(async () => {
  await generate()
  bs.init({
    startPath: '/graphacademy/',
    server: './public'
  })
})()
