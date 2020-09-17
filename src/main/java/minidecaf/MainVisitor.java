package minidecaf;

import minidecaf.MiniDecafParser.*;
import minidecaf.Type.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.util.*;

/**
 * 这里实现了编译器的主要逻辑。为了简单，本框架仅通过在分析树上单次遍历来生成目标汇编代码。
 *
 * 遍历时会为每一个分析树节点计算一个类型（Type），对于没有类型的节点用 NoType 留空。
 * 这样做的目的在前 10 个 step 并不会体现出来，是为了方便 step 11 和 12 所需的对于表达式的类型推导。
 *
 * @author   Namasikanam
 * @since    2020-09-11
 */
public final class MainVisitor extends MiniDecafBaseVisitor<Type> {
    private StringBuilder sb; // 生成的目标汇编代码
    MainVisitor(StringBuilder sb) {
        this.sb = sb;
    }

    @Override
    public Type visitProg(ProgContext ctx) {
        for (var child: ctx.children)
            visit(child);
        
        // 将未初始化的全局变量发送到 bss 段
        for (String global: declaredGlobalTable.keySet())
            if (initializedGlobalTable.get(global) == null) {
                sb.append("\t.comm " + global + ", " + 
                    declaredGlobalTable.get(global).getSize() + ", 4\n"); // 大小为 4，对齐字节数也为 4
            }

        if (!containsMain) reportError("no main function", ctx);

        return new NoType();
    }

    @Override
    public Type visitDeclaredFunc(DeclaredFuncContext ctx) {
        String funcName = ctx.IDENT(0).getText();
        if (declaredGlobalTable.get(funcName) != null)
            reportError("a global variable and a function have the same funcName", ctx);

        Type returnType = visit(ctx.type(0));
        List<Type> paramTypes = new ArrayList<>();
        for (int i = 1; i < ctx.type().size(); ++i)
            paramTypes.add(visit(ctx.type(i)));
        FunType funType = new FunType(returnType, paramTypes);

        if (declaredFuncTable.get(funcName) != null && !declaredFuncTable.get(funcName).equals(funType))
            reportError("declare a function with two different signatures", ctx);
        
        // 这里我们与 gcc 保持一致，同样签名的函数可以声明任意多次

        declaredFuncTable.put(funcName, funType);

        return new NoType();
    }
    
    @Override
    public Type visitDefinedFunc(DefinedFuncContext ctx) {
        currentFunc = ctx.IDENT(0).getText();
        if (declaredGlobalTable.get(currentFunc) != null)
            reportError("a global variable and a function have the same name", ctx);
        if (currentFunc.equals("main")) containsMain = true;

        sb.append("\t.text\n") // 表示以下内容在 text 段中
          .append("\t.global " + currentFunc + "\n") // 让该 label 对链接器可见
          .append(currentFunc + ":\n");
        if (definedFuncTable.get(currentFunc) != null)
            reportError("define two functions as a same name", ctx);
        
        // 计算函数的签名
        Type returnType = visit(ctx.type(0));
        List<Type> paramTypes = new ArrayList<>();
        for (int i = 1; i < ctx.type().size(); ++i)
            paramTypes.add(visit(ctx.type(i)));
        FunType funType = new FunType(returnType, paramTypes);

        if (declaredFuncTable.get(currentFunc) != null && !declaredFuncTable.get(currentFunc).equals(funType))
            reportError("the signature of the defined function is not the same as it is declared", ctx);
        
        declaredFuncTable.put(currentFunc, funType);
        definedFuncTable.put(currentFunc, funType);
        
        sb.append("# prologue\n");
        push("ra");
        push("fp");
        sb.append("\tmv fp, sp\n");
        int backtracePos = sb.length();
        localCount = 0;

        // 为这个函数体开启一个新的作用域
        symbolTable.add(new HashMap<>());

        // 将函数的参数作为局部变量取出
        // 这里参数的存储方式遵循 riscv gcc 的调用约定
        for (int i = 1; i < ctx.IDENT().size(); ++i) {
            String paraName = ctx.IDENT().get(i).getText();
            if (symbolTable.peek().get(paraName) != null)
                reportError("two parameters have the same name", ctx);
            
            if (i < 9) {
                ++localCount;
                sb.append("\tsw a" + (i - 1) + ", " + (-4 * i) + "(fp)\n");
                symbolTable.peek().put(paraName,
                    new Symbol(paraName, -4 * i,
                        funType.paramTypes.get(i - 1).valueCast(ValueCat.LVALUE)));
            } else {
                symbolTable.peek().put(paraName,
                    new Symbol(paraName, 4 * (i - 9 + 2),
                        funType.paramTypes.get(i - 1).valueCast(ValueCat.LVALUE)));
            }
        }

        // 发射函数体
        for (var blockItem: ctx.blockItem())
            visit(blockItem);
        
        // 关闭这个函数体的作用域
        symbolTable.pop();
        
        // 为了实现方便，我们默认 return 0
        sb.append("# return 0 as default\n")
          .append("\tli t1, 0\n")
          .append("\taddi sp, sp, -4\n")
          .append("\tsw t1, 0(sp)\n");
        
        // 根据局部变量的数量，回填所需的栈空间
        sb.insert(backtracePos, "\taddi sp, sp, " + (-4 * localCount) + "\n");

        sb.append("# epilogue\n")
          .append(".exit." + currentFunc + ":\n")
          .append("\tlw a0, 0(sp)\n")
          .append("\tmv sp, fp\n");
        pop("fp");
        pop("ra");
        sb.append("\tret\n\n");

        return new NoType();
    }

    @Override
    public Type visitGlobalIntOrPointerDecl(GlobalIntOrPointerDeclContext ctx) {
        // 这里我们与 gcc 保持一致，全局变量可以多次声明，但只能被初始化一次。
        String name = ctx.IDENT().getText();
        if (declaredFuncTable.get(name) != null)
            reportError("a global variable and a function have the same name", ctx);
        
        Type type = visit(ctx.type());
        if (declaredGlobalTable.get(name) != null && !declaredGlobalTable.get(name).equals(type))
            reportError("different global variables with same name are declared", ctx);
        declaredGlobalTable.put(name, type.valueCast(ValueCat.LVALUE));

        var num = ctx.NUM();
        if (num != null) {
            if (initializedGlobalTable.get(name) != null)
                reportError("try initializing a global variable twice", ctx);
            initializedGlobalTable.put(name, type.valueCast(ValueCat.RVALUE));

            sb.append("\t.data\n") // 全局变量要放在 data 段中
              .append("\t.align 4\n") // 4 字节对齐
              .append(name + ":\n")
              .append("\t.word " + num.getText() + "\n"); // word 表示一个 32 位字
        }
        return new NoType();
    }

    @Override
    public Type visitGlobalArrayDecl(GlobalArrayDeclContext ctx) {
        String name = ctx.IDENT().getText();
        if (declaredFuncTable.get(name) != null)
            reportError("a global variable and a function have the same name", ctx);

        Deque<Type> types = new ArrayDeque<>();
        types.add(visit(ctx.type()).valueCast(ValueCat.LVALUE));
        for (int i = ctx.NUM().size() - 1; i >= 0; --i) {
            int x = Integer.valueOf(ctx.NUM(i).getText());
            if (x == 0)
                reportError("the dimension of array cannot be 0", ctx);
            types.addFirst(new ArrayType(types.getFirst(), x));
        }
        Type type = types.getFirst();

        if (declaredGlobalTable.get(name) != null && !declaredGlobalTable.get(name).equals(type))
            reportError("different global variables with same name are declared", ctx);
        declaredGlobalTable.put(name, type);
        
        return new NoType();
    }

    @Override
    public Type visitLocalIntOrPointerDecl(LocalIntOrPointerDeclContext ctx) {
        String name = ctx.IDENT().getText();
        if (symbolTable.peek().get(name) != null)
            reportError("try declaring a declared variable", ctx);
        
        // 加入符号表
        Type type = visit(ctx.type());
        symbolTable.peek().put(name,
            new Symbol(name, -4 * ++localCount, type.valueCast(ValueCat.LVALUE)));
        
        // 如果有初始化表达式的话，求出初始化表达式的值
        var expr = ctx.expr();
        if (expr != null) {
            Type exprType = castToRValue(visit(expr), ctx);
            if (!exprType.equals(type))
                reportError("initialize value of type " + exprType + " to some variable of type " + type, ctx);

            pop("t0");
            sb.append("# initialize local variable\n")
              .append("\tsw t0, " + (-4 * localCount) + "(fp)\n");
        }
        
        return new NoType();
    }

    @Override
    public Type visitLocalArrayDecl(LocalArrayDeclContext ctx) {
        String name = ctx.IDENT().getText();
        if (symbolTable.peek().get(name) != null)
            reportError("try declaring a declared variable", ctx);

        // 计算数组的类型
        Deque<Type> types = new ArrayDeque<>();
        types.add(visit(ctx.type()).valueCast(ValueCat.LVALUE));
        for (int i = ctx.NUM().size() - 1; i >= 0; --i) {
            int x = Integer.valueOf(ctx.NUM(i).getText());
            if (x == 0)
                reportError("the dimension of array cannot be 0", ctx);
            types.addFirst(
                new ArrayType(types.getFirst(), x));
        }
        assert types.getFirst() instanceof ArrayType;
        ArrayType type = (ArrayType)types.getFirst();

        // 数组等价于若干个全局变量
        symbolTable.peek().put(name,
            new Symbol(name, -4 * (localCount += type.getSize() / 4), type));

        return new NoType();
    }

    @Override
    public Type visitExprStmt(ExprStmtContext ctx) {
        var expr = ctx.expr();
        if (expr != null) {
            visit(ctx.expr());
            pop("t0"); // 这个表达式不再会被使用，需要将其从栈里弹出
        }
        return new NoType();
    }

    @Override
    public Type visitReturnStmt(ReturnStmtContext ctx) {
        Type returnType = castToRValue(visit(ctx.expr()), ctx);
        Type expectedType = definedFuncTable.get(currentFunc).returnType;
        if (!expectedType.equals(returnType))
            reportError("return type " + returnType + " is inconsitent with expected return type " + expectedType, ctx);

        // 函数返回，返回值存在 a0 中
        sb.append("\tj .exit." + currentFunc + "\n");
        return new NoType();
    }

    @Override
    public Type visitIfStmt(IfStmtContext ctx) {
        int currentCondNo = condNo++;
        sb.append("# # if\n"); // "#if" 是宏，所以这里需要多给一个"#"

        // 根据条件表达式的值判断是否要直接跳转至 else 分支
        typeCheck(visit(ctx.expr()), IntType.class, ctx);
        pop("t0");
        sb.append("\tbeqz t0, .else" + currentCondNo + "\n");

        visit(ctx.stmt(0));
        sb.append("\tj .afterCond" + currentCondNo + "\n") // 在 then 分支结束后直接跳至分支语句末尾
          .append(".else" + currentCondNo + ":\n"); // 标记 else 分支开始部分的 label
        if (ctx.stmt().size() > 1)
            visit(ctx.stmt(1));
        sb.append(".afterCond" + currentCondNo + ":\n");
        return new NoType();
    }

    @Override
    public Type visitBlockStmt(BlockStmtContext ctx) {
        // 开启一个新的作用域
        symbolTable.add(new HashMap<>());
        for (var blockItem: ctx.blockItem())
            visit(blockItem);
        // 关闭作用域
        symbolTable.pop();
        return new NoType();
    }

    @Override
    public Type visitWhileStmt(WhileStmtContext ctx) {
        int currentLoopNo = loopNo++;
        sb.append("# while\n")
          .append(".beforeLoop" + currentLoopNo + ":\n")
          .append(".continueLoop" + currentLoopNo + ":\n"); // continue 指令需要跳转到这里
        typeCheck(visit(ctx.expr()), IntType.class, ctx);
        pop("t0");
        sb.append("\tbeqz t0, .afterLoop" + currentLoopNo + "\n");

        // 访问循环体
        loopNos.push(currentLoopNo);
        visit(ctx.stmt());
        loopNos.pop();

        sb.append("\tj .beforeLoop" + currentLoopNo + "\n")
          .append(".afterLoop" + currentLoopNo + ":\n");
        return new NoType();
    }

    @Override
    public Type visitForStmt(ForStmtContext ctx) {
        int currentLoopNo = loopNo++;
        sb.append("# for\n");
        
        // for 循环里的表达式均可能为空，故这里先把各个表达式照出来
        ExprContext initExpr = null;
        ExprContext condExpr = null;
        ExprContext afterExpr = null;
        for (int i = 0; i < ctx.children.size(); ++i)
            if (ctx.children.get(i) instanceof ExprContext) {
                ExprContext expr = (ExprContext)(ctx.children.get(i));
                if (ctx.children.get(i - 1).getText().equals("("))
                    initExpr = expr;
                else if (ctx.children.get(i + 1).getText().equals(";"))
                    condExpr = expr;
                else
                    afterExpr = expr;
            }
        
        // 开启一个新的作用域
        symbolTable.add(new HashMap<>());
        
        // 由于语法限制，下面两种情况不会同时发生
        if (initExpr != null) {
            visit(initExpr);
            sb.append("\taddi sp, sp, 4\n");
        }
        if (ctx.localDecl() != null) visit(ctx.localDecl());

        sb.append(".beforeLoop" + currentLoopNo + ":\n");
        if (condExpr != null) {
            typeCheck(visit(condExpr), IntType.class, ctx);
            sb.append("\tlw t1, 0(sp)\n")
              .append("\taddi sp, sp, 4\n")
              .append("\tbeqz t1, .afterLoop" + currentLoopNo + "\n");
        }

        // 访问循环体
        loopNos.push(currentLoopNo);
        symbolTable.add(new HashMap<>()); // 这里需要开一个新的作用域
        visit(ctx.stmt());
        symbolTable.pop();
        loopNos.pop();

        sb.append(".continueLoop" + currentLoopNo + ":\n"); // continue 指令需要跳转到这里
        if (afterExpr != null) {
            visit(afterExpr);
            sb.append("\taddi sp, sp, 4\n");
        }
        symbolTable.pop();

        sb.append("\tj .beforeLoop" + currentLoopNo + "\n")
          .append(".afterLoop" + currentLoopNo + ":\n");
        return new NoType();
    }

    @Override
    public Type visitDoStmt(DoStmtContext ctx) {
        int currentLoopNo = loopNo++;
        sb.append("# do-while\n");

        // 访问循环体
        sb.append(".beforeLoop" + currentLoopNo + ":\n");
        loopNos.push(currentLoopNo);
        visit(ctx.stmt());
        loopNos.pop();

        sb.append(".continueLoop" + currentLoopNo + ":\n"); // continue 指令需要跳转到这里
        typeCheck(visit(ctx.expr()), IntType.class, ctx);
        pop("t0");
        sb.append("\tbnez t0, .beforeLoop" + currentLoopNo + "\n")
          .append(".afterLoop" + currentLoopNo + ":\n");
        
        return new NoType();
    }

    @Override
    public Type visitBreakStmt(BreakStmtContext ctx) {
        if (loopNos.isEmpty())
            reportError("break statement not within loop", ctx);
        sb.append("\tj .afterLoop" + loopNos.peek() + "\n");
        return new NoType();
    }

    @Override
    public Type visitContinueStmt(ContinueStmtContext ctx) {
        if (loopNos.isEmpty())
            reportError("continue statement not within loop", ctx);
        sb.append("\tj .continueLoop" + loopNos.peek() + "\n");
        return new NoType();
    }

    @Override
    public Type visitExpr(ExprContext ctx) {
        if (ctx.children.size() > 1) {
            Type unaryType = typeCheck(visit(ctx.unary()), Type.class, ValueCat.LVALUE, ctx);
            Type exprType = castToRValue(visit(ctx.expr()), ctx);
            if (!exprType.equals(unaryType.valueCast(ValueCat.RVALUE)))
                reportError("assign value of type " + exprType + " to some variable of type " + unaryType, ctx);
            pop("t1");
            pop("t0");
            sb.append("# assign\n")
              .append("\tsw t1, 0(t0)\n");
            push("t0");
            return unaryType;
        } else return visit(ctx.ternary());
    }

    @Override
    public Type visitTernary(TernaryContext ctx) {
        if (ctx.children.size() > 1) {
            int currentCondNo = condNo++;
            sb.append("# ternary conditional\n");
            typeCheck(visit(ctx.lor()), IntType.class, ctx);

            // 根据条件表达式的值判断是否要跳转至 else 分支
            pop("t0");
            sb.append("\tbeqz t0, .else" + currentCondNo + "\n");
            Type thenType = castToRValue(visit(ctx.expr()), ctx);
            sb.append("\tj .afterCond" + currentCondNo + "\n") // 在 then 分支结束后直接跳至分支语句末尾
              .append(".else" + currentCondNo + ":\n"); // 在 else 分支结束后直接跳至分支语句末尾
            Type elseType = castToRValue(visit(ctx.ternary()), ctx);
            sb.append(".afterCond" + currentCondNo + ":\n");

            if (!thenType.equals(elseType))
                reportError("different types of branches of a ternary", ctx);
            return thenType;
        } else return visit(ctx.lor());
    }

    @Override
    public Type visitLor(LorContext ctx) {
        if (ctx.children.size() > 1) {
            typeCheck(visit(ctx.lor(0)), IntType.class, ctx);
            typeCheck(visit(ctx.lor(1)), IntType.class, ctx);
            
            pop("t1");
            pop("t0");
            sb.append("\tsnez t0, t0\n")
              .append("\tsnez t1, t1\n")
              .append("\tor t0, t0, t1\n");
            push("t0");
            
            return new IntType();
        } else {
            return visit(ctx.land());
        }
    }

    @Override
    public Type visitLand(LandContext ctx) {
        if (ctx.children.size() > 1) {
            typeCheck(visit(ctx.land(0)), IntType.class, ctx);
            typeCheck(visit(ctx.land(1)), IntType.class, ctx);

            pop("t1");
            pop("t0");
            sb.append("\tsnez t0, t0\n")
              .append("\tsnez t1, t1\n")
              .append("\tand t0, t0, t1\n");
            push("t0");
            
            return new IntType();
        } else {
            return visit(ctx.equ());
        }
    }

    @Override
    public Type visitEqu(EquContext ctx) {
        if (ctx.children.size() > 1) {
            Type leftType = castToRValue(visit(ctx.equ(0)), ctx);
            Type rightType = castToRValue(visit(ctx.equ(1)), ctx);
            if (!leftType.equals(rightType)) {
                reportError("the types of the both sides of \"==\"/\"!=\" must be same", ctx);
            }
            if (leftType instanceof ArrayType || rightType instanceof ArrayType) {
                reportError("array type cannot take part in equational operation", ctx);
            }
            
            pop("t1");
            pop("t0");
            String op = ctx.children.get(1).getText();
            if (op.equals("==")) {
                sb.append("# eq\n")
                  .append("\tsub t0, t0, t1\n")
                  .append("\tseqz t0, t0\n");
                push("t0");
            } else {
                assert op.equals("!=");
                sb.append("# ne\n")
                  .append("\tsub t0, t0, t1\n")
                  .append("\tsnez t0, t0\n");
                push("t0");
            }

            return new IntType();
        } else {
            return visit(ctx.rel());
        }
    }

    @Override
    public Type visitRel(RelContext ctx) {
        if (ctx.children.size() > 1) {
            typeCheck(visit(ctx.rel(0)), IntType.class, ctx);
            typeCheck(visit(ctx.rel(1)), IntType.class, ctx);

            pop("t1");
            pop("t0");
            String op = ctx.children.get(1).getText();
            if (op.equals("<")) {
                sb.append("# <\n")
                  .append("\tslt t0, t0, t1\n");
            } else if (op.equals("<=")) {
                sb.append("# <=\n")
                  .append("\tsgt t0, t0, t1\n")
                  .append("\txori t0, t0, 1\n");
            } else if (op.equals(">")) {
                sb.append("# >\n")
                  .append("\tsgt t0, t0, t1\n");
            } else {
                assert op.equals(">=");
                sb.append("# >=\n")
                  .append("\tslt t0, t0, t1\n")
                  .append("\txori t0, t0, 1\n");
            }
            push("t0");

            return new IntType();
        } else {
            return visit(ctx.add());
        }
    }

    @Override
    public Type visitAdd(AddContext ctx) {
        if (ctx.children.size() > 1) {
            Type leftType = castToRValue(visit(ctx.add(0)), ctx);
            Type rightType = castToRValue(visit(ctx.add(1)), ctx);
            if (ctx.children.get(1).getText().equals("+")) {
                pop("t1");
                pop("t0");
                if (leftType instanceof IntType && rightType instanceof IntType) {
                    sb.append("# int + int\n")
                      .append("\tadd t0, t0, t1\n");
                    push("t0");
                    return new IntType();
                } else if (leftType instanceof PointerType && rightType instanceof IntType) {
                    sb.append("# pointer + int\n")
                      .append("\tslli t1, t1, 2\n")
                      .append("\tadd t0, t0, t1\n");
                    push("t0");
                    return leftType;
                } else if (leftType instanceof IntType && rightType instanceof PointerType) {
                    sb.append("# int + pointer\n")
                      .append("\tslli t0, t0, 2\n")
                      .append("\tadd t0, t0, t1\n");
                    push("t0");
                    return rightType;
                } else {
                    reportError("only the followings are legal for addition operation: 1. pointer + integer 2. integer + pointer 3. integer + integer", ctx);
                    return new NoType();
                }
            } else {
                pop("t1");
                pop("t0");
                if (leftType instanceof IntType && rightType instanceof IntType) {
                    sb.append("# int - int\n")
                      .append("\tsub t0, t0, t1\n");
                    push("t0");
                    return new IntType();
                }
                else if (leftType instanceof PointerType && rightType instanceof IntType) {
                    sb.append("# pointer - int\n")
                      .append("\tslli t1, t1, 2\n")
                      .append("\tsub t0, t0, t1\n");
                    push("t0");
                    return leftType;
                }
                else if (leftType instanceof PointerType && rightType.equals(leftType)) {
                    sb.append("# pointer - pointer\n")
                      .append("\tsub t0, t0, t1\n")
                      .append("\tsrai t0, t0, 2\n");
                    push("t0");
                    return new IntType();
                }
                else {
                    reportError("only the followings are legal for subtraction operation: 1. pointer - integer 2. integer - integer", ctx);
                    return new NoType();
                }
            }
        } else return visit(ctx.mul());
    }

    @Override
    public Type visitMul(MulContext ctx) {
        if (ctx.children.size() > 1) {
            typeCheck(visit(ctx.mul(0)), IntType.class, ctx);
            typeCheck(visit(ctx.mul(1)), IntType.class, ctx);
            String op = ctx.children.get(1).getText();
            op = op.equals("*") ? "mul" : op.equals("/") ? "div" : "rem";
            pop("t1");
            pop("t0");
            sb.append("# " + op + "\n")
              .append("\t" + op + " t0, t0, t1\n");
            push("t0");
            return new IntType();
        } else return visit(ctx.unary());
    }

    @Override
    public Type visitOpUnary(OpUnaryContext ctx) {
        Type type = visit(ctx.unary());
        String op = ctx.children.get(0).getText();
        
        if (op.equals("*")) {
            return castToRValue(type, ctx).dereferenced();
        } if (op.equals("&")) {
            return type.referenced();
        } else {
            typeCheck(type, IntType.class, ctx);
            pop("t0");
            if (op.equals("-")) {
                sb.append("# " + op + " int\n")
                  .append("\tneg t0, t0\n");
            } else if (op.equals("!")) {
                sb.append("# " + op + " int\n")
                  .append("\tseqz t0, t0\n");
            } else {
                assert(op.equals("~"));
                sb.append("# " + op + " int\n")
                  .append("\tnot t0, t0\n");
            }
            push("t0");
            return new IntType();
        }
    }

    @Override
    public Type visitCastUnary(CastUnaryContext ctx) {
        Type srcType = visit(ctx.unary());
        Type dstType = visit(ctx.type());
        return dstType.valueCast(srcType.valueCat);
    }

    @Override
    public Type visitPostfixUnary(PostfixUnaryContext ctx) {
        Type type = visit(ctx.postfix());
        return type;
    }

    @Override
    public Type visitCallPostfix(CallPostfixContext ctx) {
        String name = ctx.IDENT().getText();
        if (declaredFuncTable.get(name) == null)
            reportError("try calling an undeclared function", ctx);
        FunType funType = declaredFuncTable.get(name);
        if (funType.paramTypes.size() != ctx.expr().size())
            reportError("the number of arguments is not equal to the number of parameters", ctx);
        
        // 这里我们遵循 riscv gcc 的调用约定
        sb.append("# prepare arguments\n");
        for (int i = ctx.expr().size() - 1; i >= 0; --i) {
            Type type = castToRValue(visit(ctx.expr().get(i)), ctx);
            if (!type.equals(funType.paramTypes.get(i)))
                reportError("the type of argument " + i + " is different from the type of parameter " + i + " of function " + name, ctx);
            if (i < 8) pop("a" + i); // 前 8 个参数使用寄存器 a0 - a7 传递
        }

        sb.append("\tcall " + name + "\n");
        
        // 弹出栈里的参数
        if (ctx.expr().size() > 8)
            sb.append("\taddi sp, sp, " + (4 * (ctx.expr().size() - 8)) + "\n");

        push("a0"); // 函数的返回值存储在 a0 中
        return funType.returnType;
    }

    @Override
    public Type visitSubscriptPostfix(SubscriptPostfixContext ctx) {
        Type postfixType = castToRValue(visit(ctx.postfix()), ctx);
        typeCheck(visit(ctx.expr()), IntType.class, ValueCat.RVALUE, ctx);
        pop("t1");
        pop("t0");
        if (postfixType instanceof PointerType) {
            sb.append("# subscript applied to a pointer\n")
              .append("\tslli t1, t1, 2\n")
              .append("\tadd t0, t0, t1\n");
            push("t0");
            return postfixType.dereferenced();
        } else if (postfixType instanceof ArrayType) {
            Type baseType = ((ArrayType)postfixType).baseType;
            sb.append("# subscript applied to an array\n")
              .append("\tli t2, " + baseType.getSize() + "\n")
              .append("\tmul t1, t1, t2\n")
              .append("\tadd t0, t0, t1\n");
            push("t0");
            return baseType;
        } else {
            reportError("the subscript operator could only be applied to a pointer or an array", ctx);
            return new NoType();
        }
    }

    @Override
    public Type visitPrimaryPostfix(PrimaryPostfixContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Type visitNumPrimary(NumPrimaryContext ctx) {
        TerminalNode num = ctx.NUM();

        // 数字字面量不能超过整型的最大值
        if (compare(Integer.toString(Integer.MAX_VALUE), num.getText()) == -1)
            reportError("too large number", ctx);

        sb.append("# number " + num.getText() + "\n")
          .append("\tli t0, " + num.getText() + "\n");
        push("t0");
        return new IntType();
    }

    @Override
    public Type visitIdentPrimary(IdentPrimaryContext ctx) {
        String name = ctx.IDENT().getText();
        Optional<Symbol> optionSymbol = lookupSymbol(name);
        if (!optionSymbol.isEmpty()) {
            Symbol symbol = optionSymbol.get();
            sb.append("# read variable " + name + " (as lvalue)\n")
              .append("\taddi t0, fp, " + symbol.offset + "\n");
            push("t0");
            return symbol.type;
        } else if (declaredGlobalTable.get(name) != null) { // 全局变量
            // 由于 32 位地址超过了长度为 4 字节的 riscv 指令的表示能力，
            // 所以这里需要用两条指令。
            sb.append("# read global variable " + name + " (as lvalue)\n")
              .append("\tlui t0, %hi(" + name + ")\n")
              .append("\taddi t0, t0, %lo(" + name + ")\n");
            push("t0");
            return declaredGlobalTable.get(name);
        } else {
            reportError("use variable that is not defined", ctx);
            return new NoType();
        }
    }

    @Override
    public Type visitParenthesizedPrimary(ParenthesizedPrimaryContext ctx) {
        return visit(ctx.expr());
    }

    // 这个方法与其他的访问函数不太一样，它的返回值是一个单纯的“类型”，
    // 而不是“某一个表达式的类型”。
    @Override
    public Type visitType(TypeContext ctx) {
        int starNum = ctx.children.size() - 1;
        if (starNum == 0) return new IntType();
        else return new PointerType(starNum);
    }

    /* 函数相关 */
    private Map<String, FunType> declaredFuncTable = new HashMap<>();
    private Map<String, FunType> definedFuncTable = new HashMap<>();
    private String currentFunc;
    private boolean containsMain = false;

    /* 符号相关 */
    private int localCount;
    private Stack<Map<String, Symbol>> symbolTable = new Stack<>(); // 符号表，所有作用域构成一个栈
    private Map<String, Type> declaredGlobalTable = new HashMap<>();
    private Map<String, Type> initializedGlobalTable = new HashMap<>();

    private Optional<Symbol> lookupSymbol(String v) {
        // 优先在内层作用域中寻找对应的符号
        for (int i = symbolTable.size() - 1; i >= 0; --i) {
            var map = symbolTable.elementAt(i);
            if (map.containsKey(v))
                return Optional.of(map.get(v));
        }
        return Optional.empty();
    }

    /* 控制语句相关 */
    private int loopNo = 0; // 用于给循环标号，避免 label 名称冲突
    private Stack<Integer> loopNos = new Stack<>(); // 当前位置的循环标号，因为可能有多层循环嵌套，所以需要用栈来维护
    private int condNo = 0; // 用于给条件语句和条件表达式所用的 label 编号，避免 label 名称冲突

    /* 类型和值类别相关 */
    /**
     * 类型和值类别的转换，转换时检查是否可行。
     *
     * @param actualType 转换前的实际类型
     * @param expectedType 期望被转换到的类型
     * @param neededValueCat 所需的值类别
     * @return 转换后的结果类型
     */
    private Type typeCheck(Type actualType, Class<?> expectedType, ValueCat neededValueCat, ParserRuleContext ctx) {
        if (!expectedType.isAssignableFrom(actualType.getClass()))
            reportError("type " + actualType + " appears, but " + expectedType.getName() + " is expected", ctx);
        if (neededValueCat == ValueCat.LVALUE && actualType.valueCat == ValueCat.RVALUE)
            reportError("an lvalue is needed here", ctx);
        if (neededValueCat == ValueCat.RVALUE && actualType.valueCat == ValueCat.LVALUE) {
            pop("t0");
            sb.append("# cast lvalue to rvalue\n")
              .append("\tlw t0, 0(t0)\n");
            push("t0");
            return actualType.valueCast(ValueCat.RVALUE);
        }
        return actualType.valueCast(neededValueCat);
    }

    // 缺省值类别，默认为右值
    private Type typeCheck(Type actualType, Class<?> expectedType, ParserRuleContext ctx) {
        return typeCheck(actualType, expectedType, ValueCat.RVALUE, ctx);
    }

    // 不作类型转换，仅仅要求右值
    private Type castToRValue(Type actualType, ParserRuleContext ctx) {
        return typeCheck(actualType, Type.class, ValueCat.RVALUE, ctx);
    }

    /* 一些工具方法 */
    /**
     * 比较大整数 s 和 t 的大小，可能的结果为小于（-1）、等于（0）或大于（1）。
     * 这里 s 和 t 以字符串的形式给出，要求它们仅由数字 0-9 组成。
     */
    private int compare(String s, String t) {
        if (s.length() != t.length())
            return s.length() < t.length() ? -1 : 1;
        else {
            for (int i = 0; i < s.length(); ++i)
                if (s.charAt(i) != t.charAt(i))
                    return s.charAt(i) < t.charAt(i) ? -1 : 1;
            return 0;
        }
    }

    /**
     * 报错，并输出错误信息和错误位置。
     * 
     * @param s 错误信息
     * @param ctx 发生错误的环境，用于确定错误的位置
     */
    private void reportError(String s, ParserRuleContext ctx) {
        throw new RuntimeException("Error("
            + ctx.getStart().getLine() + ", "
            + ctx.getStart().getCharPositionInLine() + "): " + s + ".\n");
    }

    /**
     * 将寄存器的值压入栈中。
     * 
     * @param reg 待压栈的寄存器
     */
    private void push(String reg) {
        sb.append("# push " + reg + "\n")
          .append("\taddi sp, sp, -4\n")
          .append("\tsw " + reg + ", 0(sp)\n");
    }

    /**
     * 将栈顶的值弹出到寄存器中。
     *
     * @param reg 用于存储栈顶值的寄存器
     */
    private void pop(String reg) {
        sb.append("# pop " + reg + "\n")
          .append("\tlw " + reg + ", 0(sp)\n")
          .append("\taddi sp, sp, 4\n");
    }
}