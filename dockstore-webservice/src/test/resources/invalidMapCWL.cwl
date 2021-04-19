cwlVersion: v1.0
class: Workflow

inputs:
  input_file: File
  template_file: File

outputs:
  output_file:
    type: File
    outputSource: hello-world/output

steps:
  hello-world:
    run:
      id: dockstore-tool-helloworld.cwl
      fake: "there should be an $import or $include here instead"
    in:
      input_file: input_file
      template_file: template_file
    out: [output]
  hello-world2:
    run: dockstore-tool-helloworld2.cwl
    in:
      input_file: input_file
      template_file: template_file
    out: [output]
