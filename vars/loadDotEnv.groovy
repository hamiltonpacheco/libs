def call(String path) {
  return readFile(file: path, encoding: "UTF-8")
    .split("\n")
    .collect({ it.trim() })
    .findAll({ it != null && it != "" })
    .inject([:], { vars, line ->
      def (key, val) = line.split("=")
      vars.put(key, val)
      return vars
    })
}