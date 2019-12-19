def call(Map args) {
  def files = args.files
  def regex = args.regex
  def replacement = args.replacement

  files
    .collect({ [content: readFile(file: it), file: it] })
    .collect({ [content: it.content.replaceAll(regex, replacement), file: it.file] })
    .each({ writeFile(file: it.file, text: it.content) })
}