parser grammar MDParser;

options {
    tokenVocab = MDLexer;
}

@parser::postinclude {

#include <string>
#include <vector>

#include "ASTNode.h"

}

program returns [std::shared_ptr<ProgramNode> node]
        : funcs EOF
          {
            $node = $funcs.node;
          }
        ;

funcs   returns [std::shared_ptr<ProgramNode> node]
        : func
          {
            $node = ProgramNode::make({$func.node});
          }
        | part=funcs func
          {
            $node = $part.node;
            $node->funcs_.push_back($func.node);
          }
        ;

func    returns [std::shared_ptr<FunctionNode> node]
        : INT Identifier '(' args ')' '{' stmtSeq '}'
          {
            $node = FunctionNode::make($Identifier.text, $args.nodes, $stmtSeq.node);
          }
        ;

stmtSeq returns [std::shared_ptr<StmtSeqNode> node]
        : /* empty */
          {
            $node = StmtSeqNode::make({});
          }
        | part=stmtSeq stmt
          {
            $node = $part.node;
            $node->stmts_.push_back($stmt.node);
          }
        ;

stmt    returns [std::shared_ptr<StmtNode> node]
        : expr ';'
          {
            $node = InvokeNode::make($expr.node);
          }
        | varDefs ';'
          {
            $node = StmtSeqNode::make($varDefs.nodes);
          }
        | var '=' expr ';'
          {
            $node = AssignNode::make($var.node, $expr.node);
          }
        | IF '(' expr ')' stmt
          {
            $node = IfThenElseNode::make($expr.node, $stmt.node);
          }
        | IF '(' expr ')' thenCase=stmt ELSE elseCase=stmt
          {
            $node = IfThenElseNode::make($expr.node, $thenCase.node, $elseCase.node);
          }
        | WHILE '(' expr ')' stmt
          {
            $node = WhileNode::make($expr.node, $stmt.node);
          }
        | RETURN expr ';'
          {
            $node = ReturnNode::make($expr.node);
          }
        | ';'
          {
            $node = StmtSeqNode::make({});
          }
        | '{' stmtSeq '}'
          {
            $node = $stmtSeq.node;
          }
        ;

expr    returns [std::shared_ptr<ExprNode> node]
        : Integer
          {
            $node = IntegerNode::make(std::stoi($Integer.text));
          }
        | var
          {
            $node = $var.node;
          }
        | Identifier '(' exprs ')'
          {
            $node = CallNode::make($Identifier.text, $exprs.nodes);
          }
        | '(' expr ')'
          {
            $node = $expr.node;
          }
        | '-' expr
          {
            $node = SubNode::make(IntegerNode::make(0), $expr.node);
          }
        | '+' expr
          {
            $node = $expr.node;
          }
        | lhs=expr op=('*' | '/') rhs=expr
          {
            if ($op.text == "*") {
                $node = MulNode::make($lhs.node, $rhs.node);
            } else {
                $node = DivNode::make($lhs.node, $rhs.node);
            }
          }
        | lhs=expr op=('+' | '-') rhs=expr
          {
            if ($op.text == "+") {
                $node = AddNode::make($lhs.node, $rhs.node);
            } else {
                $node = SubNode::make($lhs.node, $rhs.node);
            }
          }
        | lhs=expr op=('<' | '>' | '<=' | '>=' | '==' | '!=') rhs=expr
          {
            if ($op.text == "<") {
                $node = LTNode::make($lhs.node, $rhs.node);
            } else if ($op.text == ">") {
                $node = GTNode::make($lhs.node, $rhs.node);
            } else if ($op.text == "<=") {
                $node = LENode::make($lhs.node, $rhs.node);
            } else if ($op.text == ">=") {
                $node = GENode::make($lhs.node, $rhs.node);
            } else if ($op.text == "==") {
                $node = EQNode::make($lhs.node, $rhs.node);
            } else if ($op.text == "!=") {
                $node = NENode::make($lhs.node, $rhs.node);
            }
          }
        ;

exprs   returns [std::vector<std::shared_ptr<ExprNode>> nodes]
        : /* empty */
        | expr
          {
            $nodes = {$expr.node};
          }
        | part=exprs ',' expr
          {
            $nodes = $part.nodes;
            $nodes.push_back($expr.node);
          }
        ;

var     returns [std::shared_ptr<VarNode> node]
        : Identifier
          {
            $node = VarNode::make($Identifier.text);
          }
        ;

vars    returns [std::vector<std::shared_ptr<VarNode>> nodes]
        : /* empty */
        | var
          {
            $nodes = {$var.node};
          }
        | part=vars ',' var
          {
            $nodes = $part.nodes;
            $nodes.push_back($var.node);
          }
        ;

varDef  returns [std::shared_ptr<StmtNode> node]
        : INT var
          {
            $node = VarDefNode::make($var.node);
          }
        | INT var '=' expr
          {
            $node = StmtSeqNode::make({
                        VarDefNode::make($var.node),
                        AssignNode::make($var.node, $expr.node)});
          }
        ;

varDefs returns [std::vector<std::shared_ptr<StmtNode>> nodes]
        : varDef
          {
            $nodes = {$varDef.node};
          }
        | part=varDefs ',' varDef
          {
            $nodes = $part.nodes;
            $nodes.push_back($varDef.node);
          }
        ;

arg     returns [std::shared_ptr<VarNode> node]
        : INT var
          {
            $node = $var.node;
          }
        ;

args    returns [std::vector<std::shared_ptr<VarNode>> nodes]
        : /* empty */
        | arg
          {
            $nodes = {$arg.node};
          }
        | part=args ',' arg
          {
            $nodes = $part.nodes;
            $nodes.push_back($arg.node);
          }
        ;

