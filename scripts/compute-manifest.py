#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["python-hcl2==7.3.1", "PyYAML==6.0.2"]
# ///
"""Offline compute identity evidence. Canonical copy: ONCE; no Terraform execution.

--check freshly renders matrix rows using their recorded build commands.
--capture creates immutable baselines (existing files are never overwritten).
--directory inspects an already rendered temporary stack; never pass .colors.
Only resource blocks enter the manifest. Attributes include data queries/outputs.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import operator
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import hcl2
from lark import Tree, Token
import yaml

class EvidenceError(ValueError):
    pass


def stable(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def symbolic(value):
    return isinstance(value, dict) and "$expression" in value


def known(value):
    if symbolic(value): return False
    if isinstance(value, dict): return all(known(v) for v in value.values())
    if isinstance(value, list): return all(known(v) for v in value)
    return True


def expression_tree(tree):
    if not isinstance(tree, Tree): return tree
    return Tree(tree.data, [expression_tree(x) for x in tree.children
                            if not (isinstance(x, Tree) and x.data == 'new_line_or_comment')])


class Evaluator:
    """Evaluate the fixture subset using python-hcl2's grammar, never eval().

    Provider results stay symbolic. Unsupported syntax/functions fail closed.
    A symbolic expression is permitted in attributes, never in instance keys.
    """
    functions = {"toset", "tolist", "length", "range", "format", "formatlist",
                 "concat", "flatten", "sort", "distinct", "join", "split",
                 "tostring", "tonumber", "cidrhost", "cidrnetmask", "file",
                 "fileexists", "templatefile", "jsonencode", "base64encode",
                 "replace", "coalesce", "lookup", "element", "keys", "values",
                 "merge", "try", "can", "ceil", "floor", "min", "max",
                 "strcontains", "trimspace", "one", "cidrsubnet", "contains"}
    def __init__(self, locals_, data=None, bindings=None):
        self.locals = locals_
        self.data = data or {}
        self.bindings = bindings or {}
        self.resolving = set()

    def ref(self, name):
        if name in self.bindings: return self.bindings[name]
        if name in {"true", "false", "null"}: return {"true":True,"false":False,"null":None}[name]
        return {"$expression": ["reference", name]}

    def local(self, name):
        if name not in self.locals: raise EvidenceError(f"unresolved local.{name}")
        if name in self.resolving: raise EvidenceError(f"cyclic local.{name}")
        self.resolving.add(name)
        try: return self.value(self.locals[name])
        finally: self.resolving.remove(name)

    def expr(self, source):
        tree = expression_tree(hcl2.parses("evidence_value = " + source))
        attr = next(tree.find_data("attribute"))
        return self.node(attr.children[-1])

    def value(self, value):
        if isinstance(value, Tree): return self.node(value)
        if isinstance(value, str) and "${" in value:
            return self.expr('"' + value + '"')
        if isinstance(value, list): return [self.value(v) for v in value]
        if isinstance(value, dict): return self.body(value)
        return value

    def node(self, n):
        if isinstance(n, Token): return str(n)
        if not isinstance(n, Tree): return n
        t=str(n.data); c=n.children
        if t in {"expr_term", "string_part", "interpolation", "object_elem_key", "object_elem_value"}:
            vals=[self.node(x) for x in c if not isinstance(x,Token) or str(x) not in ['(',')','${','}']]
            if len(vals)!=1: raise EvidenceError(f"unsupported {t}: {n.pretty()}")
            return vals[0]
        if t=="identifier": return self.ref(str(c[0]))
        if t=="int_lit": return int(''.join(str(x) for x in c))
        if t=="float_lit": return float(''.join(str(x) for x in c))
        if t=="string":
            vals=[self.node(x) for x in c]
            if len(vals)==1 and not isinstance(vals[0],str): return vals[0]
            if all(known(x) for x in vals): return ''.join(str(x) for x in vals)
            return {"$expression":["interpolate",vals]}
        if t=="tuple": return [self.node(x) for x in c if isinstance(x,Tree)]
        if t=="get_attr_expr_term":
            base=self.node(c[0]); name=str(c[1].children[0].children[0])
            if base=={"$expression":["reference","local"]}: return self.local(name)
            if isinstance(base,dict) and not symbolic(base):
                if name not in base: raise EvidenceError(f"missing attribute {name}")
                return base[name]
            result={"$expression":["attribute",base,name]}
            # data.TYPE.NAME is a query, not a managed resource identity.
            ref=result["$expression"]
            if symbolic(base) and base["$expression"][0]=="attribute":
                b=base["$expression"]
                if b[1]=={"$expression":["reference","data"]}:
                    key=f"{b[2]}.{name}"
                    if key not in self.data: raise EvidenceError(f"missing data query {key}")
                    return {"$expression":["data-query",b[2],self.body(self.data[key])]}
            return result
        if t=="index_expr_term":
            base=self.node(c[0]); idx=self.node(c[1].children[0])
            if known(base) and known(idx): return base[idx]
            return {"$expression":["index",base,idx]}
        if t=="function_call":
            name=str(c[0].children[0])
            if name not in self.functions: raise EvidenceError(f"unsupported function {name}")
            args=[self.node(x) for x in c[1].children if isinstance(x,Tree)] if len(c)>1 else []
            if all(known(x) for x in args):
                funcs={"toset":lambda a:sorted({stable(x):x for x in a}.values(),key=stable),
                       "tolist":list,"length":len,"range":lambda *a:list(range(*a)),
                       "format":lambda f,*a:f % tuple(a),"concat":lambda *a:sum(a,[]),
                       "sort":sorted,"distinct":lambda a:list({stable(x):x for x in a}.values()),
                       "join":lambda sep,a:sep.join(a),"split":lambda sep,a:a.split(sep),
                       "tostring":str,"tonumber":int,"keys":lambda a:sorted(a),
                       "values":lambda a:[a[k] for k in sorted(a)],"jsonencode":stable,
                       "element":lambda a,i:a[i % len(a)],"lookup":lambda a,k,d=None:a.get(k,d),
                       "replace":lambda a,b,c:a.replace(b,c),"min":min,"max":max,
                       "strcontains":lambda a,b:b in a,"trimspace":lambda a:a.strip(),
                       "one":lambda a:a[0] if len(a)==1 else None,"contains":lambda a,b:b in a}
                if name in funcs: return funcs[name](*args)
            # file/fileexists are deliberately symbolic: never read operator files.
            return {"$expression":["call",name,args]}
        if t=="binary_op":
            if isinstance(c[1],Tree) and c[1].data=='binary_term' and isinstance(c[1].children[1],Tree) and c[1].children[1].data=='conditional':
                rhs=c[1].children[1]
                test=Tree('binary_op',[c[0],Tree('binary_term',[c[1].children[0],rhs.children[0]])])
                return self.node(Tree('conditional',[test,*rhs.children[1:]]))
            left=self.node(c[0]); term=c[1]; op=str(term.children[0].children[0]);right=self.node(term.children[1])
            funcs={"+":operator.add,"-":operator.sub,"*":operator.mul,"/":operator.truediv,"%":operator.mod,
                   "==":operator.eq,"!=":operator.ne,">":operator.gt,"<":operator.lt,">=":operator.ge,"<=":operator.le,
                   "&&":lambda a,b:a and b,"||":lambda a,b:a or b}
            if op not in funcs: raise EvidenceError(f"unsupported operator {op}")
            if known(left) and known(right): return funcs[op](left,right)
            return {"$expression":[op,left,right]}
        if t=="conditional":
            cond=self.node(c[0])
            if known(cond): return self.node(c[1] if cond else c[2])
            return {"$expression":["conditional",cond,self.node(c[1]),self.node(c[2])]}
        if t=="unary_op":
            op=str(c[0]); val=self.node(c[1])
            if known(val): return -val if op=='-' else not val
            return {"$expression":[op,val]}
        if t in {"for_tuple_expr", "for_object_expr"}:
            intro=next(x for x in c if isinstance(x,Tree) and x.data=='for_intro')
            names=[str(x.children[0]) for x in intro.children if isinstance(x,Tree) and x.data=='identifier']
            seq=self.node([x for x in intro.children if isinstance(x,Tree)][-1])
            if not known(seq):
                old = self.bindings.copy()
                try:
                    self.bindings.update({name:{"$expression":["iteration",i]} for i,name in enumerate(names)})
                    bodies = [self.node(x.children[-1]) if x.data == 'for_cond' else self.node(x)
                              for x in c if isinstance(x,Tree) and x is not intro]
                    return {"$expression":[t,seq,bodies]}
                finally: self.bindings = old
            expressions=[x for x in c if isinstance(x,Tree) and x is not intro]
            result=[] if t=='for_tuple_expr' else {}
            old=self.bindings.copy()
            try:
                for key,val in (seq.items() if isinstance(seq,dict) else enumerate(seq)):
                    self.bindings.update(dict(zip(names,[key,val] if len(names)==2 else [val])))
                    items=[x for x in expressions if x.data!='for_cond']
                    conditions=[x for x in expressions if x.data=='for_cond']
                    if conditions and not self.node(conditions[0].children[-1]): continue
                    if isinstance(result,list): result.append(self.node(items[0]))
                    else: result[self.node(items[0])]=self.node(items[1])
            finally:self.bindings=old
            return result
        if t=="object":
            result={}
            for elem in c:
                if not isinstance(elem,Tree) or elem.data=="new_line_or_comment":continue
                keynode=elem.children[0]
                if keynode.data=='object_elem_key' and keynode.children[0].data=='identifier':key=str(keynode.children[0].children[0])
                else:key=self.node(keynode)
                if not isinstance(key,str):raise EvidenceError('symbolic object key')
                result[key]=self.node(elem.children[-1])
            return result
        if t in {"heredoc_template", "heredoc_template_trim"}:
            raw=str(c[0])
            if '${' in raw: raise EvidenceError('interpolated heredoc unsupported')
            return raw
        if t in {"attr_splat_expr_term", "full_splat_expr_term"}:
            return {"$expression":[t,self.node(c[0]),n.pretty()]}
        raise EvidenceError(f"unsupported expression node {t}")

    def body(self, body):
        result={}
        for key,val in sorted(body.items()):
            if key=='dynamic':continue
            result[key]=self.value(val)
        for block in body.get('dynamic',[]):
            for label,defn in block.items():
                seq=self.value(defn['for_each'])
                if not known(seq):raise EvidenceError(f"symbolic dynamic {label} collection")
                iterator=defn.get('iterator',label)
                if isinstance(iterator,Tree):iterator=str(iterator.children[0].children[0])
                if isinstance(iterator,str) and iterator.startswith('${'):iterator=iterator[2:-1]
                old=self.bindings.copy()
                expanded=[]
                try:
                    for key,val in (seq.items() if isinstance(seq,dict) else enumerate(seq)):
                        self.bindings[iterator]={'key':key,'value':val}
                        expanded.extend(self.body(x) for x in defn['content'])
                finally:self.bindings=old
                result.setdefault(label,[]).extend(expanded)
        # Provider set-valued nested blocks: list order does not affect semantics.
        for key in ['inbound_rule','outbound_rule','ingress','egress','security_rule','rule']:
            if isinstance(result.get(key),list):result[key]=sorted(result[key],key=stable)
        return result


def hcl_document(text):
    """Keep expression ASTs: DictTransformer stringifies embedded lists lossily."""
    def body(tree):
        result = {}
        for item in tree.children:
            if not isinstance(item, Tree) or item.data == 'new_line_or_comment':
                continue
            if item.data == 'attribute':
                key = str(item.children[0].children[0])
                if key in result:
                    raise EvidenceError(f'duplicate attribute {key}')
                result[key] = item.children[-1]
            elif item.data == 'block':
                kind = str(item.children[0].children[0])
                inner = body(item.children[-1])
                labels = item.children[1:-1]
                for label in reversed(labels):
                    name = Evaluator({}).node(label)
                    if symbolic(name) and name['$expression'][0] == 'reference':
                        name = name['$expression'][1]
                    if not isinstance(name, str):
                        raise EvidenceError('non-literal block label')
                    inner = {name: inner}
                result.setdefault(kind, []).append(inner)
            else:
                raise EvidenceError(f'unsupported HCL body node {item.data}')
        return result
    return body(expression_tree(hcl2.parses(text)).children[0])


def stack(directory):
    directory=Path(directory).resolve()
    if '.colors' in directory.parts:raise EvidenceError('refusing generated .colors as evidence source')
    files=sorted([*directory.glob('*.tf'),*directory.glob('*.tf.json')])
    if not files:raise EvidenceError(f'no Terraform files: {directory}')
    merged={'resource':{},'data':{},'locals':{},'output':{},'other':{}}
    for file in files:
        doc=json.loads(file.read_text()) if file.name.endswith('.json') else hcl_document(file.read_text())
        for kind,value in doc.items():
            if kind in ['resource','data']:
                blocks=value if isinstance(value,list) else [value]
                for block in blocks:
                    for typ,names in block.items():
                        for name,body in names.items():
                            address=f'{typ}.{name}'
                            if address in merged[kind]:raise EvidenceError(f'duplicate {kind} {address}')
                            merged[kind][address]=body
            elif kind in ['locals','output']:
                for block in (value if isinstance(value,list) else [value]):
                    for key,val in block.items():
                        if key in merged[kind]:raise EvidenceError(f'duplicate {kind}.{key}')
                        merged[kind][key]=val
            else: merged['other'].setdefault(kind,[]).extend(value if isinstance(value,list) else [value])
    ev=Evaluator(merged['locals'],merged['data']);manifest=[];attributes={}
    for addr,body in sorted(merged['resource'].items()):
        if 'count' in body and 'for_each' in body:raise EvidenceError(f'both count and for_each on {addr}')
        kind='count' if 'count' in body else 'for_each' if 'for_each' in body else 'single'
        if kind=='single':keys=[None]
        else:
            value=ev.value(body[kind])
            if not known(value):raise EvidenceError(f'unresolved cardinality {addr}')
            if kind=='count':
                if type(value)!=int or value<0:raise EvidenceError(f'invalid count on {addr}')
                keys=list(range(value))
            else:
                keys=sorted(value if isinstance(value,(dict,list)) else [],key=stable)
                if not isinstance(value,(dict,list)) or any(not isinstance(k,str) for k in keys):raise EvidenceError(f'invalid for_each on {addr}')
        manifest.append({'address':addr,'kind':kind,'keys':keys})
        instances={}
        for key in keys:
            ev.bindings={'count':{'index':key}} if kind=='count' else {'each':{'key':key,'value':value[key] if isinstance(value,dict) else key}} if kind=='for_each' else {}
            instances[stable(key)]=ev.body({k:v for k,v in body.items() if k not in ['count','for_each']})
        attributes[addr]={'kind':kind,'instances':instances}
    ev.bindings={}
    return manifest,{'resources':attributes,'data_queries':sorted([{'type':a.split('.')[0],'body':ev.body(b)} for a,b in merged['data'].items()],key=stable),'outputs':ev.value(merged['output']),'configuration':ev.value(merged['other'])}


def render_row(repo,row,temp):
    base=repo/row['base_fixture']
    opts=yaml.safe_load(base.read_text())
    opts.update(row.get('overlay',{}))
    for key in row.get('remove_keys',[]):opts.pop(key,None)
    opts['workdir']=str(temp/'work')
    fixture=temp/'fixture.yml';fixture.write_text(yaml.safe_dump(opts,sort_keys=False))
    env={k:v for k,v in os.environ.items() if not k.startswith('COLORS_PAR_') and not k.endswith('_LIB_ROOT')}
    env['COLORS_PAR_WORKDIR']=str(temp/'work')
    env['PYTHONDONTWRITEBYTECODE']='1'
    env.update({k:v.replace('{repo}',str(repo)) for k,v in row.get('environment',{}).items()})
    cmd=[x.replace('{repo}',str(repo)).replace('{fixture}',str(fixture)).replace('{temp}',str(temp)) for x in row['command']]
    if 'build' not in cmd or any(x in ['create','delete','--dry-run'] for x in cmd):raise EvidenceError('only build commands permitted')
    cwd=Path(row.get('cwd','{repo}').replace('{repo}',str(repo)).replace('{temp}',str(temp)))
    proc=subprocess.run(cmd,cwd=cwd,env=env,capture_output=True,text=True,timeout=180)
    if proc.returncode:raise EvidenceError(f"{row['id']}: build exit {proc.returncode}\n{proc.stderr[-3000:]}\n{proc.stdout[-1000:]}")
    directory=temp/'work'/row['profile']/row['compute_stage']
    if not directory.exists():raise EvidenceError(f'missing rendered stage {directory}')
    return directory


def main(kind='manifest'):
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--check',action='store_true');ap.add_argument('--capture',action='store_true')
    ap.add_argument('--directory',type=Path);ap.add_argument('--row');ap.add_argument('--repo',type=Path,default=Path(__file__).resolve().parent.parent)
    args=ap.parse_args()
    try:
        if args.directory:
            result=stack(args.directory)[0 if kind=='manifest' else 1]
            print(json.dumps(result,sort_keys=True,indent=2));return 0
        if not (args.check or args.capture):ap.error('use --check, --capture or --directory')
        repo=args.repo.resolve(); matrix=json.loads((repo/'test/fixtures/compute-matrix.json').read_text())
        rows=[r for r in matrix['rows'] if not args.row or r['id']==args.row]
        if not rows:raise EvidenceError('matrix has no matching rows; cannot claim success')
        failures=[]
        for row in rows:
            try:
                with tempfile.TemporaryDirectory(prefix='compute-evidence-') as tmp:
                    directory=render_row(repo,row,Path(tmp))
                    result=stack(directory)[0 if kind=='manifest' else 1]
                    target=repo/'test/resources'/('compute-manifests' if kind=='manifest' else 'compute-attributes')/(row['id']+('.txt' if kind=='manifest' else '.json'))
                    text=json.dumps(result,sort_keys=True,indent=2)+'\n'
                    if args.capture:
                        target.parent.mkdir(parents=True,exist_ok=True)
                        with target.open('x') as out:out.write(text)
                    else:
                        if target.read_text()!=text:raise EvidenceError(f'{row["id"]}: {kind} differs from immutable baseline')
                print(f'PASS {row["id"]} {kind}')
            except (EvidenceError,OSError,ValueError,subprocess.TimeoutExpired) as e:
                failures.append(str(e));print(f'FAIL {e}',file=sys.stderr)
        return 1 if failures else 0
    except (EvidenceError,OSError,ValueError) as e:
        print(f'ERROR {e}',file=sys.stderr);return 2

if __name__=='__main__':sys.exit(main())
