workflow myWorkflow {
    String? docker_image   = "quay.io/ucsc_cgl/verifybamid:1.3.0"

    call taglessDocker
    call latestDocker
    call parmeterizedDocker
    call versionedDocker
    call digestDocker
}

task taglessDocker {
    command {
        echo "hello world"
    }
    output {
        String out = read_string(stdout())
    }
    runtime {
        docker: "pkrusche/hap.py"
    }
}

task parmeterizedDocker {
    String docker_image
    command {
        echo "hello world"
    }
    runtime {
        docker: docker_image
    }
}

task latestDocker {
    command {
        echo "hello world"
    }
    runtime {
        docker: "pkrusche/hap.py:latest"
    }
}

task versionedDocker {
    command {
        echo "hello world"
    }
    runtime {
        docker: "pkrusche/hap.py:v1.0"
    }
}

task digestDocker {
    command {
        echo "hello world"
    }
    runtime {
        docker: "pkrusche/hap.py@sha256:f63e020c4062e0be8d081a50de16562f2ba161166e896655868efdb5527a8640"
    }
}
