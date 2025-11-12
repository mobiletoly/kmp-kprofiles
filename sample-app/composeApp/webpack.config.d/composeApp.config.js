;(function () {
  config.devServer = config.devServer || {}
  config.devServer.historyApiFallback = {
    rewrites: [{ from: /.*/, to: '/index.html' }]
  }
})()
