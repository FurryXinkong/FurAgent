# -*- coding: utf-8 -*-
"""
FurAgent 安卓端脚本元数据解析器（Chaquopy 模块）。

【为什么单独做成模块文件】
之前用 Kotlin 侧 builtins.callAttr("exec", compiled, globals) 执行一段
字符串代码，再 globals.get("__result__") 取结果。在 Chaquopy 上这条路
不可靠：exec 执行后 __result__ 取不到（返回空或不存在），导致所有脚本
都被判定为"解析失败"，表现为 create 成功但 list 为空。

改成真正的 Python 模块后，用 py.getModule("skill_parser").callAttr(...)
调用，返回值直接是 Python 对象，不依赖 exec 的命名空间行为。

【解析规则】
只取模块级 Assign 节点（NAME / DESCRIPTION / PARAMS / TIMEOUT / CONFIG），
用 ast.literal_eval 求值，绝不 exec 用户代码。
"""

import ast
import json

_META_KEYS = ("NAME", "DESCRIPTION", "PARAMS", "TIMEOUT", "CONFIG")


def parse_meta(path):
    """
    解析脚本文件头部的元数据。

    :param path: .py 文件绝对路径
    :return: JSON 字符串 {"ok": true, "meta": {...}}
             或 {"ok": false, "error": "..."}
    """
    try:
        with open(path, "r", encoding="utf-8") as f:
            src = f.read()
    except Exception as e:
        return json.dumps(
            {"ok": False, "error": "读取失败 %s: %s" % (type(e).__name__, e)},
            ensure_ascii=False,
        )

    try:
        tree = ast.parse(src)
    except Exception as e:
        return json.dumps(
            {"ok": False, "error": "语法错误 %s: %s" % (type(e).__name__, e)},
            ensure_ascii=False,
        )

    meta = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        for t in node.targets:
            if not isinstance(t, ast.Name):
                continue
            if t.id not in _META_KEYS:
                continue
            try:
                meta[t.id] = ast.literal_eval(node.value)
            except Exception:
                # 单个元数据求值失败不影响其它字段
                pass

    try:
        return json.dumps({"ok": True, "meta": meta}, ensure_ascii=False)
    except Exception as e:
        return json.dumps(
            {"ok": False, "error": "序列化失败 %s: %s" % (type(e).__name__, e)},
            ensure_ascii=False,
        )


def run_script(script_path, stdin_json, cfg_json):
    """
    执行脚本，模拟 stdin/stdout。

    :param script_path: .py 文件绝对路径
    :param stdin_json: 传给脚本的 JSON 参数（一行字符串）
    :param cfg_json: 配置 JSON 字符串，空串表示无配置
    :return: JSON 字符串 {"ok": bool, "stdout": str, "stderr": str, "error": str}
    """
    import io
    import os
    import sys
    import runpy
    import traceback

    old_stdin, old_stdout, old_stderr = sys.stdin, sys.stdout, sys.stderr
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    ok = True
    error = ""

    try:
        # 模拟 stdin：预置一行 JSON 参数
        sys.stdin = io.StringIO(stdin_json + "\n")
        sys.stdout = out_buf
        sys.stderr = err_buf

        # 注入配置到环境变量（与桌面端一致）
        if cfg_json:
            os.environ["FURAGENT_SKILL_CONFIG"] = cfg_json

        runpy.run_path(script_path, run_name="__main__")
    except SystemExit:
        # 脚本主动 sys.exit() 不算错误
        pass
    except Exception as e:
        ok = False
        error = "%s: %s" % (type(e).__name__, e)
        err_buf.write(traceback.format_exc())
    finally:
        sys.stdin, sys.stdout, sys.stderr = old_stdin, old_stdout, old_stderr

    return json.dumps(
        {
            "ok": ok,
            "stdout": out_buf.getvalue(),
            "stderr": err_buf.getvalue(),
            "error": error,
        },
        ensure_ascii=False,
    )


def exec_code(code, stdin_json, work_dir):
    """
    临时执行一段 Python 代码（不落盘），模拟 stdin/stdout。

    与 run_script 的区别：代码来自字符串而不是文件，用于「临时算一下」
    「验证一段逻辑」这类一次性需求，不污染 scripts/ 目录。

    :param code: 要执行的 Python 源码
    :param stdin_json: 传给代码的 JSON 参数（一行字符串，可为空）
    :param work_dir: 工作目录（代码里 open() 相对路径的基准），空串表示不改
    :return: JSON 字符串 {"ok": bool, "stdout": str, "stderr": str, "error": str}
    """
    import io
    import os
    import sys
    import traceback

    old_stdin, old_stdout, old_stderr = sys.stdin, sys.stdout, sys.stderr
    old_cwd = None
    out_buf = io.StringIO()
    err_buf = io.StringIO()
    ok = True
    error = ""

    try:
        sys.stdin = io.StringIO((stdin_json or "") + "\n")
        sys.stdout = out_buf
        sys.stderr = err_buf

        if work_dir:
            try:
                old_cwd = os.getcwd()
                os.chdir(work_dir)
            except Exception:
                old_cwd = None

        # 用 __main__ 命名空间执行，脚本里 if __name__ == "__main__" 能生效
        globs = {"__name__": "__main__", "__file__": "<exec>"}
        exec(compile(code, "<exec>", "exec"), globs)
    except SystemExit:
        pass
    except Exception as e:
        ok = False
        error = "%s: %s" % (type(e).__name__, e)
        err_buf.write(traceback.format_exc())
    finally:
        if old_cwd is not None:
            try:
                os.chdir(old_cwd)
            except Exception:
                pass
        sys.stdin, sys.stdout, sys.stderr = old_stdin, old_stdout, old_stderr

    return json.dumps(
        {
            "ok": ok,
            "stdout": out_buf.getvalue(),
            "stderr": err_buf.getvalue(),
            "error": error,
        },
        ensure_ascii=False,
    )
