#!/usr/bin/env python3
"""Reverify raw QR-07 artifacts inside the global exact-SHA gate (no extractall)."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import xml.etree.ElementTree as ET
import verify_ret04_evidence as d
import verify_ret04_identity_e3a as a
import verify_ret04_revocation_e3b as b

WORKFLOW = "RM QR-07 R0-R2 Physical Return PostgreSQL Qualification"
SUITE = d.SUITE
CORE = frozenset((*d.METHODS,*a.METHODS,*b.METHODS))
MANIFESTS = (("ret04-d-manifest.json", d.verify, 24, "RET04-D/v1"),
             ("ret04-e3a-manifest.json", a.verify, 24, "RET04-E3A/v1"),
             ("ret04-e3b-manifest.json", b.verify, 8, "RET04-E3B/v1"))
XML = "surefire-reports/TEST-" + SUITE + ".xml"
TARGET = "qr07-r0-evidence/"
FULL = "qr07-full-pg/"
MAX_FILE = 64*1024*1024
MAX_ARCHIVE = 256*1024*1024
BAD = {"failure","error","skipped","flakyFailure","flakyError","rerunFailure","rerunError"}

def require(ok, message):
    if not ok:
        raise ValueError(message)

def safe_members(archive):
    seen=set()
    total=0
    for info in archive.infolist():
        name=info.filename
        parts=name.rstrip("/").split("/")
        require(name and not name.startswith("/") and "\\" not in name and
                all(p not in ("", ".", "..") and ":" not in p for p in parts),
                "unsafe ZIP path")
        require(name.rstrip("/") not in seen, "duplicate ZIP entry")
        seen.add(name.rstrip("/"))
        require(not stat.S_ISLNK(info.external_attr >> 16), "ZIP symlink")
        require(0<=info.file_size<=MAX_FILE, "oversized ZIP member")
        total+=info.file_size
        require(total<=MAX_ARCHIVE, "oversized ZIP contents")
    # No file may shadow an ancestor directory (order independent).
    files={i.filename for i in archive.infolist() if not i.is_dir()}
    require(all(not any(str(parent) in files for parent in PurePosixPath(p).parents)
                for p in files), "ZIP file/directory collision")
    return files

def read_sha(archive, files, path, sha):
    require(bool(re.fullmatch(r"[0-9a-f]{40}",sha)) and path in files and
            archive.read(path).decode("ascii").strip()==sha,"RET04 artifact SHA mismatch")

def verify_suite(raw, exact):
    root=ET.fromstring(raw)
    cases=root.findall("testcase")
    require(root.tag=="testsuite" and root.get("name")==SUITE and
            root.get("tests")==str(len(cases)) and
            root.get("failures")==root.get("errors")=="0","RET04 invalid Surefire suite")
    names=[c.get("name") for c in cases]
    require(len(names)==len(set(names)) and all(names),"RET04 duplicate/unnamed testcase")
    selected=[c for c in cases if c.get("name") in CORE]
    require(len(CORE)==56 and len(selected)==56 and
            all(c.get("classname")==SUITE and not any(x.tag in BAD for x in c) for c in selected),
            "RET04 missing or nonclean core testcase")
    require(root.get("skipped")==str(sum(c.find("skipped") is not None for c in cases)),
            "RET04 invalid skipped count")
    if exact:
        require(len(cases)==81 and root.get("skipped")=="0" and
                all(c.get("classname")==SUITE and not any(x.tag in BAD for x in c) for c in cases),
                "RET04 target must execute 81 clean tests")
    return len(cases)

def verify_target_archive(archive, sha):
    files=safe_members(archive)
    read_sha(archive,files,TARGET+"exact-sha.txt",sha)
    require(XML in files,"RET04 target Surefire XML missing")
    require({p for p in files if PurePosixPath(p).name.startswith("TEST-") and p.endswith(".xml")}=={XML},
            "RET04 unexpected target Surefire suites")
    raw=archive.read(XML)
    verify_suite(raw,True)
    # Copy only the canonical evidence subtree, never arbitrary archive paths.
    with tempfile.TemporaryDirectory(prefix="ret04-global-") as directory:
        root=Path(directory)
        xml=root/"suite.xml"
        xml.write_bytes(raw)
        evidence=root/"evidence"
        evidence.mkdir()
        allowed={"exact-sha.txt","console.log",*(x[0] for x in MANIFESTS)}
        folders={"sql","identity-e3","revocation-e3b","revocation-iam"}
        for path in sorted(files):
            if not path.startswith(TARGET):
                continue
            rel=PurePosixPath(path[len(TARGET):])
            require((len(rel.parts)==1 and rel.name in allowed) or
                    (len(rel.parts)==3 and rel.parts[0] in folders and rel.suffix==".tsv"),
                    "unexpected RET04 evidence file")
            if rel.name=="console.log":
                continue
            dest=evidence.joinpath(*rel.parts)
            dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_bytes(archive.read(path))
        results=[]
        # D, A then B: B consumes prior manifests only after independent
        # recomputation and deep equality against their published contents.
        for name, verifier, count, schema in MANIFESTS:
            path=evidence/name
            require(path.is_file(),"missing RET04 manifest "+name)
            published=json.loads(path.read_text(encoding="utf-8"))
            actual=verifier(xml,evidence,sha)
            require(published==actual,"RET04 manifest differs from recomputed evidence: "+name)
            require(actual.get("schema")==schema and actual.get("status")=="PASS" and
                    actual.get("sha")==sha and actual.get("cases")==count and
                    actual.get("surefireSha256")==hashlib.sha256(raw).hexdigest(),
                    "RET04 manifest provenance mismatch")
            results.append({"name":name,"schema":schema,"cases":count,
                            "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                            "methods":[x["method"] for x in actual["evidence"]]})
        method_list=[x for item in results for x in item["methods"]]
        require(len(method_list)==56 and set(method_list)==CORE,"RET04 combined method coverage mismatch")
        return {"status":"PASS","sha":sha,"cases":56,"javaTests":81,
                "surefireSha256":hashlib.sha256(raw).hexdigest(),"manifests":results}

def verify_full_archive(archive, sha):
    files=safe_members(archive)
    read_sha(archive,files,FULL+"exact-sha.txt",sha)
    require(XML in files,"RET04 full regression suite missing")
    raw=archive.read(XML)
    count=verify_suite(raw,False)
    require(count>=81,"RET04 full regression suite incomplete")
    return {"status":"PASS","sha":sha,"coreCases":56,"suiteTests":count,
            "surefireSha256":hashlib.sha256(raw).hexdigest()}
