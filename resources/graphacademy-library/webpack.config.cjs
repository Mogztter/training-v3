const path = require('path')

module.exports = {
  entry: './src/index.js',
  output: {
    libraryExport: 'default',
    libraryTarget: 'umd',
    library: 'GraphAcademyPage',
    filename: 'graphacademy.js',
    path: path.resolve(__dirname, 'dist'),
  },
}
